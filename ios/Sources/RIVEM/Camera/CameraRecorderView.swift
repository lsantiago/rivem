#if os(iOS)
import SwiftUI
import UIKit

/// Full-screen recorder: preview, 30/60 fps, focus/exposure/white-balance lock, record/stop. Calls `onFinished`
/// with the recorded file (in the temporary folder) and closes.
struct CameraRecorderView: View {
    let onFinished: (URL) -> Void

    @Environment(\.dismiss) private var dismiss
    @StateObject private var recorder = CameraRecorder()
    @State private var fps: Int = 30
    @State private var locked = false

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()
                CameraPreview(session: recorder.session).ignoresSafeArea()
                VStack(spacing: 12) {
                    CameraTopBar(recorder: recorder, close: close)
                    if geo.size.height > geo.size.width && !recorder.isRecording {
                        Label("Gira el iPhone en horizontal y apóyalo firme (trípode)", systemImage: "rotate.right")
                            .font(.callout)
                            .padding(8)
                            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
                    }
                    Spacer()
                    CameraControls(recorder: recorder, fps: $fps, locked: $locked, record: toggleRecording)
                }
                .padding()
            }
        }
        .onAppear { recorder.start(fps: fps) }
        .onDisappear { recorder.stop() }
        .onChange(of: fps) { _, value in recorder.changeFrameRate(value) }
        .onChange(of: locked) { _, value in recorder.setLocked(value) }
        .onChange(of: recorder.lastRecording) { _, url in
            if let u = url {
                onFinished(u)
                dismiss()
            }
        }
    }

    private func close() {
        recorder.stop()
        dismiss()
    }

    private func toggleRecording() {
        if recorder.isRecording {
            recorder.stopRecording()
        } else {
            let scene: UIWindowScene? = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
            recorder.startRecording(rotationAngle: CameraPreview.rotationAngle(for: scene?.interfaceOrientation))
        }
    }
}

private struct CameraTopBar: View {
    @ObservedObject var recorder: CameraRecorder
    let close: () -> Void

    var body: some View {
        HStack {
            Button(action: close) {
                Image(systemName: "xmark.circle.fill").font(.title)
            }
            .disabled(recorder.isRecording)
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(recorder.isRecording ? String(format: "● %.0f s", recorder.elapsed) : recorder.status)
                    .font(.callout.bold())
                    .foregroundStyle(recorder.isRecording ? Color.red : Color.white)
                if !recorder.formatDescription.isEmpty {
                    Text("\(recorder.formatDescription) · estabilización desactivada")
                        .font(.caption)
                        .foregroundStyle(.white)
                }
                if let e = recorder.errorMessage {
                    Text(e).font(.caption).foregroundStyle(.red)
                }
            }
            .padding(8)
            .background(Color.black.opacity(0.4), in: RoundedRectangle(cornerRadius: 8))
        }
        .foregroundStyle(.white)
    }
}

private struct CameraControls: View {
    @ObservedObject var recorder: CameraRecorder
    @Binding var fps: Int
    @Binding var locked: Bool
    let record: () -> Void

    var body: some View {
        HStack(spacing: 20) {
            VStack(alignment: .leading, spacing: 8) {
                Picker("FPS", selection: $fps) {
                    Text("30 fps").tag(30)
                    Text("60 fps").tag(60)
                }
                .pickerStyle(.segmented)
                .frame(width: 160)
                .disabled(recorder.isRecording || !recorder.isConfigured)
                Toggle(isOn: $locked) {
                    Text("Bloquear foco, exposición y balance").font(.caption)
                }
                .frame(width: 220)
                .disabled(!recorder.isConfigured)
            }
            .padding(8)
            .background(Color.black.opacity(0.4), in: RoundedRectangle(cornerRadius: 8))
            .foregroundStyle(.white)
            Spacer()
            Button(action: record) {
                ZStack {
                    Circle().stroke(Color.white, lineWidth: 4).frame(width: 72, height: 72)
                    if recorder.isRecording {
                        RoundedRectangle(cornerRadius: 6).fill(Color.red).frame(width: 30, height: 30)
                    } else {
                        Circle().fill(Color.red).frame(width: 58, height: 58)
                    }
                }
            }
            .disabled(!recorder.isConfigured)
            .accessibilityLabel(recorder.isRecording ? "Detener" : "Grabar")
        }
    }
}
#endif
