#if os(iOS)
import AVFoundation
import Combine
import SwiftUI
import UIKit

/// Video recorder for LSPIV: back wide-angle camera, fixed frame rate (30 or 60 fps set through
/// `activeVideoMin/MaxFrameDuration` on a format that supports it), **video stabilisation off** (it warps the image
/// differently in each frame and breaks the georeferencing), optional focus / exposure / white-balance lock, no
/// audio (no microphone permission needed). On a device without camera (simulator) it reports it and does nothing.
final class CameraRecorder: NSObject, ObservableObject, AVCaptureFileOutputRecordingDelegate {
    @Published private(set) var isConfigured = false
    @Published private(set) var isRecording = false
    @Published private(set) var status: String = "Preparando la cámara…"
    @Published private(set) var formatDescription: String = ""
    @Published private(set) var elapsed: Double = 0
    @Published var lastRecording: URL? = nil
    @Published var errorMessage: String? = nil

    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "com.rivem.camera")
    private let output = AVCaptureMovieFileOutput()
    private var device: AVCaptureDevice?
    private var timer: Timer?
    private var recordingStarted: Date?

    /// Asks for permission and configures the session at `fps`.
    func start(fps: Int) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            queue.async { self.configure(fps: fps) }
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                if granted {
                    self.queue.async { self.configure(fps: fps) }
                } else {
                    self.publish(status: "Sin permiso para usar la cámara (Ajustes › RIVEM).")
                }
            }
        default:
            publish(status: "Sin permiso para usar la cámara (Ajustes › RIVEM).")
        }
    }

    func stop() {
        queue.async {
            if self.output.isRecording { self.output.stopRecording() }
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    private func publish(status: String, configured: Bool? = nil, format: String? = nil) {
        DispatchQueue.main.async {
            self.status = status
            if let c = configured { self.isConfigured = c }
            if let f = format { self.formatDescription = f }
        }
    }

    // MARK: Configuration (on `queue`)

    private func configure(fps: Int) {
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            publish(status: "No hay cámara disponible (¿simulador?). Importa un video desde Fotos o Archivos.", configured: false)
            AppLog.info("Cámara: no hay dispositivo de video")
            return
        }
        device = camera
        session.beginConfiguration()
        if session.inputs.isEmpty {
            do {
                let input = try AVCaptureDeviceInput(device: camera)
                guard session.canAddInput(input) else { throw PipelineError("la sesión no acepta la cámara") }
                session.addInput(input)
            } catch {
                session.commitConfiguration()
                publish(status: "No se pudo abrir la cámara: \(error.localizedDescription)", configured: false)
                AppLog.error("Cámara: \(error)")
                return
            }
        }
        if session.outputs.isEmpty {
            guard session.canAddOutput(output) else {
                session.commitConfiguration()
                publish(status: "No se puede grabar video en este dispositivo.", configured: false)
                return
            }
            session.addOutput(output)
        }
        session.commitConfiguration()
        if !session.isRunning { session.startRunning() }
        // Format and frame duration after the session runs, so no preset change can reset them.
        let description: String = applyFormat(camera, fps: fps)
        configureConnection()
        publish(status: "Lista", configured: true, format: description)
        AppLog.info("Cámara configurada: \(description)")
    }

    /// Picks the format closest to 1920×1080 that supports `fps` (falls back to the largest one that does) and locks
    /// the frame duration. Setting `activeFormat` switches the session preset to `.inputPriority`.
    private func applyFormat(_ camera: AVCaptureDevice, fps: Int) -> String {
        let wanted = Double(fps)
        var best: AVCaptureDevice.Format? = nil
        var bestScore = Double.infinity
        for format in camera.formats {
            let supports: Bool = format.videoSupportedFrameRateRanges.contains { $0.minFrameRate <= wanted && wanted <= $0.maxFrameRate }
            guard supports else { continue }
            let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            let score: Double = abs(Double(dims.width) - 1920) + abs(Double(dims.height) - 1080)
            if score < bestScore {
                bestScore = score
                best = format
            }
        }
        guard let format = best else { return "formato por defecto (\(fps) fps no soportado)" }
        do {
            try camera.lockForConfiguration()
            camera.activeFormat = format
            let duration = CMTime(value: 1, timescale: CMTimeScale(fps))
            camera.activeVideoMinFrameDuration = duration
            camera.activeVideoMaxFrameDuration = duration
            if camera.isFocusModeSupported(.continuousAutoFocus) { camera.focusMode = .continuousAutoFocus }
            if camera.isExposureModeSupported(.continuousAutoExposure) { camera.exposureMode = .continuousAutoExposure }
            camera.unlockForConfiguration()
        } catch {
            AppLog.error("Cámara: no se pudo fijar el formato: \(error)")
        }
        let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        return "\(dims.width)×\(dims.height) a \(fps) fps"
    }

    private func configureConnection() {
        guard let connection = output.connection(with: .video) else { return }
        if connection.isVideoStabilizationSupported {
            connection.preferredVideoStabilizationMode = .off
        }
    }

    // MARK: Controls

    /// Locks (true) or frees focus, exposure and white balance at their current values.
    func setLocked(_ locked: Bool) {
        queue.async {
            guard let camera = self.device else { return }
            do {
                try camera.lockForConfiguration()
                if locked {
                    if camera.isFocusModeSupported(.locked) { camera.focusMode = .locked }
                    if camera.isExposureModeSupported(.locked) { camera.exposureMode = .locked }
                    if camera.isWhiteBalanceModeSupported(.locked) { camera.whiteBalanceMode = .locked }
                } else {
                    if camera.isFocusModeSupported(.continuousAutoFocus) { camera.focusMode = .continuousAutoFocus }
                    if camera.isExposureModeSupported(.continuousAutoExposure) { camera.exposureMode = .continuousAutoExposure }
                    if camera.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) { camera.whiteBalanceMode = .continuousAutoWhiteBalance }
                }
                camera.unlockForConfiguration()
            } catch {
                AppLog.error("Cámara: bloqueo de foco/exposición: \(error)")
            }
        }
    }

    func changeFrameRate(_ fps: Int) {
        queue.async {
            guard let camera = self.device, !self.output.isRecording else { return }
            let description: String = self.applyFormat(camera, fps: fps)
            self.configureConnection()
            self.publish(status: "Lista", format: description)
        }
    }

    /// Starts recording with the rotation of the current interface orientation (frames come out upright).
    func startRecording(rotationAngle: CGFloat) {
        queue.async {
            guard !self.output.isRecording, self.session.isRunning else { return }
            if let connection = self.output.connection(with: .video) {
                if connection.isVideoRotationAngleSupported(rotationAngle) { connection.videoRotationAngle = rotationAngle }
                if connection.isVideoStabilizationSupported { connection.preferredVideoStabilizationMode = .off }
            }
            let name = "rivem-\(Int(Date().timeIntervalSince1970)).mov"
            let url: URL = FileManager.default.temporaryDirectory.appendingPathComponent(name)
            try? FileManager.default.removeItem(at: url)
            self.output.startRecording(to: url, recordingDelegate: self)
            DispatchQueue.main.async {
                self.isRecording = true
                self.recordingStarted = Date()
                self.elapsed = 0
                self.timer?.invalidate()
                self.timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
                    guard let self = self, let start = self.recordingStarted else { return }
                    self.elapsed = Date().timeIntervalSince(start)
                }
            }
        }
    }

    func stopRecording() {
        queue.async {
            if self.output.isRecording { self.output.stopRecording() }
        }
    }

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL,
                    from connections: [AVCaptureConnection], error: Error?) {
        // AVFoundation reports some benign errors with the file complete (e.g. maximum duration reached).
        var ok = true
        if let e = error as NSError? {
            ok = (e.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool) ?? false
            AppLog.error("Grabación terminada con aviso: \(e.localizedDescription)")
        }
        DispatchQueue.main.async {
            self.timer?.invalidate()
            self.timer = nil
            self.isRecording = false
            if ok {
                self.lastRecording = outputFileURL
                AppLog.info("Grabación guardada: \(outputFileURL.lastPathComponent), \(String(format: "%.1f", self.elapsed)) s")
            } else {
                self.errorMessage = "La grabación falló: \(error?.localizedDescription ?? "error desconocido")"
            }
        }
    }
}

/// Live preview (AVCaptureVideoPreviewLayer) that follows the interface orientation.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

        var previewLayer: AVCaptureVideoPreviewLayer {
            // layerClass guarantees the type.
            layer as! AVCaptureVideoPreviewLayer
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            let angle: CGFloat = CameraPreview.rotationAngle(for: window?.windowScene?.interfaceOrientation)
            if let connection = previewLayer.connection, connection.isVideoRotationAngleSupported(angle) {
                connection.videoRotationAngle = angle
            }
        }
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspect
        view.backgroundColor = .black
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        uiView.setNeedsLayout()
    }

    /// Back camera: 0° = landscape with the home indicator on the right (`.landscapeRight` interface), 90° = portrait.
    static func rotationAngle(for orientation: UIInterfaceOrientation?) -> CGFloat {
        switch orientation {
        case .some(.landscapeRight): return 0
        case .some(.landscapeLeft): return 180
        case .some(.portraitUpsideDown): return 270
        default: return 90
        }
    }
}
#endif
