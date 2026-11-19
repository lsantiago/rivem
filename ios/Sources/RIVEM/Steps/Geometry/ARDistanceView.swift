import RiverCore
import SwiftUI
#if os(iOS) && !targetEnvironment(simulator)
import ARKit
import SceneKit
#endif

/// RIVEM addition: the six oblique distances measured with the phone instead of a tape. ARKit tracks the phone in a
/// gravity-aligned frame; the user aims the crosshair at the four control points in order and each one is placed
/// where the ray from the screen centre meets the surface (LiDAR mesh on Pro models, visual depth otherwise). The
/// distances are horizontal (`SurveyedDistances`), as the oblique solution puts the points on the water plane.
struct ARDistanceView: View {
    let onUse: (SurveyedDistances) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var points: [Point3] = []
    /// Camera-to-point distance of each point (m).
    @State private var ranges: [Double] = []
    @State private var status: String = "Mueve el teléfono despacio para que reconozca el entorno."
    @State private var hasLiDAR = false
    @State private var addRequest = 0
    @State private var undoRequest = 0
    @State private var failure: String? = nil

    private static let colors: [Color] = [.red, .yellow, .green, .cyan]

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                #if os(iOS) && !targetEnvironment(simulator)
                if ARWorldTrackingConfiguration.isSupported {
                    ARSurveyView(addRequest: addRequest, undoRequest: undoRequest, status: $status, hasLiDAR: $hasLiDAR,
                                 failure: $failure) { (p: Point3, range: Double) in
                        points.append(p)
                        ranges.append(range)
                    } onUndo: {
                        if !points.isEmpty { points.removeLast() }
                        if !ranges.isEmpty { ranges.removeLast() }
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

    private var crosshair: some View {
        let next: Int = min(points.count, 3)
        let color: Color = points.count < 4 ? Self.colors[next] : .white
        return ZStack {
            Circle().stroke(color, lineWidth: 2).frame(width: 34, height: 34)
            Rectangle().fill(color).frame(width: 2, height: 14)
            Rectangle().fill(color).frame(width: 14, height: 2)
        }
        .shadow(color: .black, radius: 2)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private var statusBar: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(status).font(.footnote)
            Text(hasLiDAR ? "LiDAR activo: preciso hasta ~5 m del teléfono." : "Sin LiDAR: profundidad visual, menos precisa.")
                .font(.caption2)
                .foregroundStyle(.secondary)
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
            if points.count < 4 {
                Text("Apunta la mira al punto \(points.count + 1) y toca «Marcar». Mismo orden que en la imagen: 1 y 2 en una orilla, 3 y 4 en la otra.")
                    .font(.callout)
            }
            if !points.isEmpty {
                HStack(spacing: 12) {
                    ForEach(0..<points.count, id: \.self) { (i: Int) in
                        VStack(spacing: 1) {
                            Circle().fill(Self.colors[i]).frame(width: 10, height: 10)
                            Text("P\(i + 1)").font(.caption2.bold())
                            Text("a \(Fmt.num(ranges[i], 1)) m").font(.caption2.monospacedDigit())
                                .foregroundStyle(ranges[i] > 5 ? Color.orange : Color.secondary)
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
                .disabled(points.isEmpty)
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
                        Label("Marcar punto \(points.count + 1)", systemImage: "scope")
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
        }
        .padding()
        .background(.ultraThinMaterial)
    }

    private var survey: SurveyedDistances? { SurveyedDistances.from(points) }

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
            if ranges.contains(where: { $0 > 5 }) {
                Text("Algún punto está a más de 5 m: la precisión cae. Contrasta al menos una distancia con cinta.")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
    }
}

#if os(iOS) && !targetEnvironment(simulator)
/// ARSCNView with gravity-aligned world tracking (+ LiDAR mesh when available). A change of `addRequest` raycasts
/// from the screen centre and reports the hit; `undoRequest` removes the last marker.
private struct ARSurveyView: UIViewRepresentable {
    let addRequest: Int
    let undoRequest: Int
    @Binding var status: String
    @Binding var hasLiDAR: Bool
    @Binding var failure: String?
    let onPoint: (Point3, Double) -> Void
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
            c.addPoint()
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

        init(_ parent: ARSurveyView) { self.parent = parent }

        func addPoint() {
            guard let view = view, markers.count < 4 else { return }
            let center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
            var hit: ARRaycastResult? = nil
            for target in [ARRaycastQuery.Target.estimatedPlane, .existingPlaneGeometry] {
                if let q = view.raycastQuery(from: center, allowing: target, alignment: .any),
                   let r = view.session.raycast(q).first {
                    hit = r
                    break
                }
            }
            guard let r = hit, let camera = view.session.currentFrame?.camera else {
                setFailure("No se encontró una superficie en la mira. Acércate, apunta a un borde con textura o mueve el teléfono.")
                return
            }
            let t = r.worldTransform.columns.3
            let c = camera.transform.columns.3
            let range: Double = Double(simd_distance(SIMD3<Float>(t.x, t.y, t.z), SIMD3<Float>(c.x, c.y, c.z)))
            let index: Int = markers.count
            let node = SCNNode(geometry: SCNSphere(radius: 0.025))
            node.geometry?.firstMaterial?.diffuse.contents = Self.color(index)
            node.geometry?.firstMaterial?.readsFromDepthBuffer = false
            node.position = SCNVector3(t.x, t.y, t.z)
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
                let l: SCNNode = Self.line(from: previous.position, to: node.position)
                view.scene.rootNode.addChildNode(l)
                link = l
            }
            if index == 3, let first = markers.first {
                // Close the quadrilateral 4 → 1.
                let closing: SCNNode = Self.line(from: node.position, to: first.position)
                node.addChildNode(closing)
                closing.position = SCNVector3(closing.position.x - node.position.x, closing.position.y - node.position.y,
                                              closing.position.z - node.position.z)
            }
            markers.append(node)
            lines.append(link)
            let p = Point3(Double(t.x), Double(t.y), Double(t.z))
            AppLog.info(String(format: "AR distancias: punto %ld en (%.3f, %.3f, %.3f) m, a %.2f m de la cámara",
                               index + 1, p.x, p.y, p.z, range))
            DispatchQueue.main.async { self.parent.onPoint(p, range) }
        }

        func undo() {
            guard let last = markers.popLast() else { return }
            last.removeFromParentNode()
            if let l = lines.popLast() { l?.removeFromParentNode() }
            DispatchQueue.main.async { self.parent.onUndo() }
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
