import RiverCore
import SwiftUI
#if os(iOS) && !targetEnvironment(simulator)
import ARKit
import CoreImage
import SceneKit
#endif

/// RIVEM addition: the six oblique distances measured with the phone instead of a tape. ARKit tracks the phone in a
/// gravity-aligned frame; the user aims the crosshair at the four control points in order (a zoom loupe of the camera
/// image helps at distance) and each point is the average of a burst of raycasts from the screen centre (LiDAR mesh
/// on Pro models, visual depth otherwise), with its spread. The distances are horizontal (`SurveyedDistances`), as
/// the oblique solution puts the points on the water plane. The live distance to the aimed surface says when to walk
/// closer: tracking keeps the coordinates while the user moves between points.
struct ARDistanceView: View {
    let onUse: (SurveyedDistances) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var marked: [MarkedPoint] = []
    @State private var status: String = "Mueve el teléfono despacio para que reconozca el entorno."
    @State private var hasLiDAR = false
    @State private var addRequest = 0
    @State private var undoRequest = 0
    @State private var measuring = false
    @State private var failure: String? = nil
    /// Distance from the phone to the surface under the crosshair (m), live.
    @State private var aimRange: Double? = nil
    @State private var zoom: Int = 4
    @State private var loupe: CGImage? = nil

    struct MarkedPoint {
        let point: Point3
        /// Phone-to-point distance (m).
        let range: Double
        /// RMS spread of the readings (m).
        let spread: Double
    }

    private static let colors: [Color] = [.red, .yellow, .green, .cyan]
    static let zooms: [Int] = [1, 2, 4, 8]
    static let loupeSize: CGFloat = 150

    /// Reliable range of the depth: LiDAR ~5 m; visual depth degrades sooner.
    private var goodRange: Double { hasLiDAR ? 5 : 3 }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                #if os(iOS) && !targetEnvironment(simulator)
                if ARWorldTrackingConfiguration.isSupported {
                    ARSurveyView(addRequest: addRequest, undoRequest: undoRequest, zoom: zoom, loupeSize: Self.loupeSize,
                                 status: $status, hasLiDAR: $hasLiDAR, failure: $failure, measuring: $measuring,
                                 aimRange: $aimRange, loupe: $loupe) { (p: AveragedPoint, range: Double) in
                        marked.append(MarkedPoint(point: p.point, range: range, spread: p.spread))
                    } onUndo: {
                        if !marked.isEmpty { marked.removeLast() }
                    }
                    .ignoresSafeArea()
                    crosshair
                } else {
                    unsupported
                }
                #else
                unsupported
                #endif
                VStack(spacing: 0) {
                    statusBar
                    if zoom > 1, let image = loupe {
                        loupeView(image)
                            .padding(.top, 8)
                    }
                    Spacer()
                    panel
                }
            }
            .navigationTitle("Medir con la cámara")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.visible, for: .navigationBar)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") { dismiss() }
                }
            }
        }
    }

    private var unsupported: some View {
        ContentUnavailableView("AR no disponible", systemImage: "arkit",
                               description: Text("Este dispositivo no admite el seguimiento de ARKit. Mide las distancias con cinta."))
    }

    private var nextColor: Color { marked.count < 4 ? Self.colors[marked.count] : .white }

    private var crosshair: some View {
        VStack(spacing: 4) {
            ZStack {
                Circle().stroke(nextColor, lineWidth: 2).frame(width: 34, height: 34)
                Rectangle().fill(nextColor).frame(width: 2, height: 14)
                Rectangle().fill(nextColor).frame(width: 14, height: 2)
            }
            .shadow(color: .black, radius: 2)
            if let r = aimRange {
                Text("\(Fmt.num(r, 1)) m")
                    .font(.caption.bold().monospacedDigit())
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(r > goodRange ? Color.orange : Color.black.opacity(0.6)))
                    .foregroundStyle(.white)
            }
        }
        .offset(y: aimRange == nil ? 0 : 11)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func loupeView(_ image: CGImage) -> some View {
        Image(decorative: image, scale: 1)
            .resizable()
            .interpolation(.medium)
            .frame(width: Self.loupeSize, height: Self.loupeSize)
            .overlay {
                ZStack {
                    Rectangle().fill(nextColor).frame(width: 1.5, height: 26)
                    Rectangle().fill(nextColor).frame(width: 26, height: 1.5)
                }
            }
            .clipShape(Circle())
            .overlay(Circle().stroke(Color.white, lineWidth: 2))
            .overlay(alignment: .bottomTrailing) {
                Text("\(zoom)×").font(.caption2.bold()).padding(4).background(Capsule().fill(.black.opacity(0.6)))
                    .foregroundStyle(.white)
            }
            .shadow(radius: 4)
            .accessibilityHidden(true)
    }

    private var statusBar: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(status).font(.footnote)
            Text(hasLiDAR ? "LiDAR activo: preciso hasta ~5 m del teléfono." : "Sin LiDAR: profundidad visual, menos precisa.")
                .font(.caption2)
                .foregroundStyle(.secondary)
            if let r = aimRange, r > goodRange, marked.count < 4 {
                Text("La mira está a \(Fmt.num(r, 1)) m: acércate al punto para marcarlo. Puedes caminar entre puntos; las coordenadas se mantienen.")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
            if let f = failure {
                Text(f).font(.caption).foregroundStyle(.orange)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(.ultraThinMaterial)
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: 10) {
            if marked.count < 4 {
                Text("Apunta la mira al punto \(marked.count + 1) y toca «Marcar» sin mover el teléfono. Mismo orden que en la imagen: 1 y 2 en una orilla, 3 y 4 en la otra.")
                    .font(.callout)
                Picker("Zoom", selection: $zoom) {
                    ForEach(Self.zooms, id: \.self) { (z: Int) in
                        Text(z == 1 ? "Sin zoom" : "\(z)×").tag(z)
                    }
                }
                .pickerStyle(.segmented)
            }
            if !marked.isEmpty {
                HStack(spacing: 12) {
                    ForEach(0..<marked.count, id: \.self) { (i: Int) in
                        let m: MarkedPoint = marked[i]
                        VStack(spacing: 1) {
                            Circle().fill(Self.colors[i]).frame(width: 10, height: 10)
                            Text("P\(i + 1)").font(.caption2.bold())
                            Text("a \(Fmt.num(m.range, 1)) m").font(.caption2.monospacedDigit())
                                .foregroundStyle(m.range > goodRange ? Color.orange : Color.secondary)
                            Text("±\(Fmt.num(m.spread * 100, 1)) cm").font(.caption2.monospacedDigit())
                                .foregroundStyle(m.spread > 0.03 ? Color.orange : Color.secondary)
                        }
                    }
                }
            }
            if let s = survey {
                distancesGrid(s)
            }
            HStack {
                Button {
                    undoRequest += 1
                } label: {
                    Label("Deshacer", systemImage: "arrow.uturn.backward")
                }
                .buttonStyle(.bordered)
                .disabled(marked.isEmpty || measuring)
                Spacer()
                if let s = survey {
                    Button {
                        onUse(s)
                        dismiss()
                    } label: {
                        Label("Usar distancias", systemImage: "checkmark.circle.fill")
                    }
                    .buttonStyle(.borderedProminent)
                } else {
                    Button {
                        failure = nil
                        addRequest += 1
                    } label: {
                        if measuring {
                            ProgressView()
                        } else {
                            Label("Marcar punto \(marked.count + 1)", systemImage: "scope")
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(measuring)
                }
            }
        }
        .padding()
        .background(.ultraThinMaterial)
    }

    private var survey: SurveyedDistances? { SurveyedDistances.from(marked.map(\.point)) }

    private func distancesGrid(_ s: SurveyedDistances) -> some View {
        let names: [String] = ["d12", "d23", "d34", "d41", "d13", "d24"]
        let values: [Double] = s.distances.values
        return VStack(alignment: .leading, spacing: 4) {
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 4) {
                ForEach(0..<6, id: \.self) { (k: Int) in
                    Text("\(names[k]) \(Fmt.num(values[k], 2)) m").font(.caption.monospacedDigit())
                }
            }
            if s.heightSpread > 0.10 {
                Text("Los puntos difieren \(Fmt.num(s.heightSpread * 100, 0)) cm en altura: el método supone los cuatro sobre la superficie del agua. Se usan distancias horizontales.")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
            if marked.contains(where: { $0.range > goodRange || $0.spread > 0.03 }) {
                Text("Algún punto se marcó lejos o con lecturas dispersas (naranja): deshazlo y márcalo más cerca, o contrasta esa distancia con cinta.")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
    }
}

#if os(iOS) && !targetEnvironment(simulator)
/// ARSCNView with gravity-aligned world tracking (+ LiDAR mesh when available). A change of `addRequest` starts a
/// burst of raycasts from the screen centre over the next frames, averaged into one point (`AveragedPoint`);
/// `undoRequest` removes the last marker. About 12 times a second it reports the distance to the aimed surface and,
/// with zoom, a crop of the camera image around the centre.
private struct ARSurveyView: UIViewRepresentable {
    let addRequest: Int
    let undoRequest: Int
    let zoom: Int
    let loupeSize: CGFloat
    @Binding var status: String
    @Binding var hasLiDAR: Bool
    @Binding var failure: String?
    @Binding var measuring: Bool
    @Binding var aimRange: Double?
    @Binding var loupe: CGImage?
    let onPoint: (AveragedPoint, Double) -> Void
    let onUndo: () -> Void

    private static let colors: [UIColor] = [.systemRed, .systemYellow, .systemGreen, .systemCyan]

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> ARSCNView {
        let view = ARSCNView(frame: .zero)
        view.automaticallyUpdatesLighting = true
        view.session.delegate = context.coordinator
        let config = ARWorldTrackingConfiguration()
        config.worldAlignment = .gravity
        config.planeDetection = [.horizontal, .vertical]
        var lidar = false
        if ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh) {
            config.sceneReconstruction = .mesh
            lidar = true
        }
        if ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth) {
            config.frameSemantics.insert(.sceneDepth)
        }
        view.session.run(config)
        context.coordinator.view = view
        context.coordinator.lastAdd = addRequest
        context.coordinator.lastUndo = undoRequest
        DispatchQueue.main.async { hasLiDAR = lidar }
        AppLog.info("AR distancias: sesión iniciada, LiDAR \(lidar ? "sí" : "no")")
        return view
    }

    func updateUIView(_ view: ARSCNView, context: Context) {
        let c: Coordinator = context.coordinator
        c.parent = self
        if addRequest != c.lastAdd {
            c.lastAdd = addRequest
            c.startBurst()
        }
        if undoRequest != c.lastUndo {
            c.lastUndo = undoRequest
            c.undo()
        }
    }

    static func dismantleUIView(_ view: ARSCNView, coordinator: Coordinator) {
        view.session.pause()
    }

    final class Coordinator: NSObject, ARSessionDelegate {
        var parent: ARSurveyView
        weak var view: ARSCNView?
        var lastAdd = 0
        var lastUndo = 0
        private var markers: [SCNNode] = []
        /// Line from the previous marker to each marker (nil for the first).
        private var lines: [SCNNode?] = []
        private var closing: SCNNode? = nil
        /// Burst in progress: readings and frames tried.
        private var burst: [Point3]? = nil
        private var burstFrames = 0
        private var lastLive: TimeInterval = 0
        private let ciContext = CIContext(options: [.useSoftwareRenderer: false])

        static let burstReadings = 15
        static let burstMaxFrames = 45

        init(_ parent: ARSurveyView) { self.parent = parent }

        func startBurst() {
            guard markers.count < 4, burst == nil else { return }
            burst = []
            burstFrames = 0
            DispatchQueue.main.async { self.parent.measuring = true }
        }

        /// Raycast from the screen centre: the hit and its distance to the camera.
        private func aim(_ frame: ARFrame) -> (Point3, Double)? {
            guard let view = view else { return nil }
            let center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
            for target in [ARRaycastQuery.Target.estimatedPlane, .existingPlaneGeometry] {
                if let q = view.raycastQuery(from: center, allowing: target, alignment: .any),
                   let r = view.session.raycast(q).first {
                    let t = r.worldTransform.columns.3
                    let c = frame.camera.transform.columns.3
                    let range = Double(simd_distance(SIMD3<Float>(t.x, t.y, t.z), SIMD3<Float>(c.x, c.y, c.z)))
                    return (Point3(Double(t.x), Double(t.y), Double(t.z)), range)
                }
            }
            return nil
        }

        func session(_ session: ARSession, didUpdate frame: ARFrame) {
            if burst != nil {
                burstFrames += 1
                if let hit = aim(frame) { burst?.append(hit.0) }
                let readings: [Point3] = burst ?? []
                if readings.count >= Self.burstReadings || burstFrames >= Self.burstMaxFrames {
                    burst = nil
                    finishBurst(readings, frame: frame)
                }
            }
            guard frame.timestamp - lastLive > 1.0 / 12 else { return }
            lastLive = frame.timestamp
            let range: Double? = aim(frame)?.1
            let image: CGImage? = parent.zoom > 1 ? crop(frame) : nil
            DispatchQueue.main.async {
                self.parent.aimRange = range
                self.parent.loupe = image
            }
        }

        private func finishBurst(_ readings: [Point3], frame: ARFrame) {
            DispatchQueue.main.async { self.parent.measuring = false }
            guard readings.count >= 3, let averaged = AveragedPoint.from(readings), let view = view else {
                setFailure("No se encontró una superficie estable en la mira. Acércate, apunta a un borde con textura y no muevas el teléfono al marcar.")
                return
            }
            let c = frame.camera.transform.columns.3
            let p: Point3 = averaged.point
            let range: Double = Double(simd_distance(SIMD3<Float>(Float(p.x), Float(p.y), Float(p.z)), SIMD3<Float>(c.x, c.y, c.z)))
            addMarker(at: SCNVector3(Float(p.x), Float(p.y), Float(p.z)), in: view)
            AppLog.info(String(format: "AR distancias: punto %ld en (%.3f, %.3f, %.3f) m, a %.2f m, dispersión %.1f cm (%ld/%ld lecturas)",
                               markers.count, p.x, p.y, p.z, range, averaged.spread * 100, averaged.used, averaged.total))
            DispatchQueue.main.async { self.parent.onPoint(averaged, range) }
        }

        private func addMarker(at position: SCNVector3, in view: ARSCNView) {
            let index: Int = markers.count
            let node = SCNNode(geometry: SCNSphere(radius: 0.025))
            node.geometry?.firstMaterial?.diffuse.contents = Self.color(index)
            node.geometry?.firstMaterial?.readsFromDepthBuffer = false
            node.position = position
            let text = SCNText(string: "\(index + 1)", extrusionDepth: 0.5)
            text.font = UIFont.boldSystemFont(ofSize: 10)
            text.firstMaterial?.diffuse.contents = Self.color(index)
            text.firstMaterial?.readsFromDepthBuffer = false
            let label = SCNNode(geometry: text)
            label.scale = SCNVector3(0.01, 0.01, 0.01)
            label.position = SCNVector3(0.03, 0.03, 0)
            label.constraints = [SCNBillboardConstraint()]
            node.addChildNode(label)
            view.scene.rootNode.addChildNode(node)
            var link: SCNNode? = nil
            if let previous = markers.last {
                let l: SCNNode = Self.line(from: previous.position, to: position)
                view.scene.rootNode.addChildNode(l)
                link = l
            }
            if index == 3, let first = markers.first {
                let l: SCNNode = Self.line(from: position, to: first.position)
                view.scene.rootNode.addChildNode(l)
                closing = l
            }
            markers.append(node)
            lines.append(link)
        }

        func undo() {
            guard burst == nil, let last = markers.popLast() else { return }
            last.removeFromParentNode()
            if let l = lines.popLast() { l?.removeFromParentNode() }
            closing?.removeFromParentNode()
            closing = nil
            DispatchQueue.main.async { self.parent.onUndo() }
        }

        /// Square crop of the camera image around the screen centre, `zoom` × what the screen shows there.
        private func crop(_ frame: ARFrame) -> CGImage? {
            guard let view = view, view.bounds.width > 0 else { return nil }
            let orientation: CGImagePropertyOrientation
            switch view.window?.windowScene?.interfaceOrientation ?? .portrait {
            case .landscapeRight: orientation = .up
            case .landscapeLeft: orientation = .down
            case .portraitUpsideDown: orientation = .left
            default: orientation = .right
            }
            let image: CIImage = CIImage(cvPixelBuffer: frame.capturedImage).oriented(orientation)
            let extent: CGRect = image.extent
            // The view shows the camera image aspect-filled: points per image pixel.
            let s: CGFloat = max(view.bounds.width / extent.width, view.bounds.height / extent.height)
            let side: CGFloat = parent.loupeSize / s / CGFloat(max(parent.zoom, 1))
            let rect = CGRect(x: extent.midX - side / 2, y: extent.midY - side / 2, width: side, height: side)
            return ciContext.createCGImage(image.cropped(to: rect), from: rect)
        }

        private func setFailure(_ text: String) {
            DispatchQueue.main.async { self.parent.failure = text }
        }

        func session(_ session: ARSession, cameraDidChangeTrackingState camera: ARCamera) {
            let text: String
            switch camera.trackingState {
            case .normal: text = "Seguimiento correcto. Apunta la mira a cada punto."
            case .notAvailable: text = "Seguimiento no disponible."
            case .limited(.initializing): text = "Iniciando: mueve el teléfono despacio de lado a lado."
            case .limited(.excessiveMotion): text = "Mueve el teléfono más despacio."
            case .limited(.insufficientFeatures): text = "Poca textura: apunta a la orilla, rocas o vegetación."
            case .limited(.relocalizing): text = "Relocalizando…"
            case .limited: text = "Seguimiento limitado."
            }
            DispatchQueue.main.async { self.parent.status = text }
        }

        func session(_ session: ARSession, didFailWithError error: Error) {
            AppLog.error("AR distancias: \(error)")
            setFailure("Error de AR: \(error.localizedDescription)")
        }

        private static func color(_ i: Int) -> UIColor { colors[min(max(i, 0), colors.count - 1)] }

        /// Thin white cylinder from `a` to `b` (world coordinates): centred at the midpoint, its axis (local y) along a→b.
        private static func line(from a: SCNVector3, to b: SCNVector3) -> SCNNode {
            let d = SIMD3<Float>(b.x - a.x, b.y - a.y, b.z - a.z)
            let length: Float = simd_length(d)
            let cylinder = SCNCylinder(radius: 0.004, height: CGFloat(length))
            cylinder.firstMaterial?.diffuse.contents = UIColor.white
            cylinder.firstMaterial?.readsFromDepthBuffer = false
            let node = SCNNode(geometry: cylinder)
            node.simdPosition = SIMD3<Float>((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2)
            if length > 0 {
                node.simdOrientation = simd_quatf(from: SIMD3<Float>(0, 1, 0), to: d / length)
            }
            return node
        }
    }
}
#endif
