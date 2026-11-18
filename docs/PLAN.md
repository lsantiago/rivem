# Plan de RIVEM (iOS primero, Android después)

## Decisiones

| Tema | Decisión | Motivo |
|---|---|---|
| Plataforma inicial | iOS 17+ (SwiftUI), probado en iPhone 14 Pro | Es el único dispositivo disponible. iOS 17 cubre desde iPhone XS/XR, y el 14 Pro recibe actualizaciones (iOS 26). Da `Observation`, `ContentUnavailableView` y las APIs actuales de AVFoundation. |
| Núcleo | Swift puro (`RiverCore`, solo Foundation) | Mismo esquema que FreeCountTraffic. Sin OpenCV ni C++: se prueba con `swift test` en cualquier SO y se porta a Kotlin línea por línea. Las partes pesadas (FFT, remuestreo de imagen) irán detrás de protocolos con una implementación portable y, si hace falta, otra acelerada (vDSP/Metal) en la app. |
| Android | Port a Kotlin del núcleo, validado con `ci/testdata` | Igual que FreeCountTraffic. |
| Compilación | GitHub Actions, macOS (repo público: minutos ilimitados) | No hay Mac. |
| Instalación | IPA sin firmar + iloader en Windows | Sideloadly falló con Apple (-80046) en el proyecto anterior. |
| Compatibilidad | Formato de carpeta de proyecto de RIVeR 3.8.3 (`docs/desktop-format.md`) | Abrir en escritorio lo medido en campo y viceversa. |
| Defectos de RIVeR | Se corrigen en el port | Pedido explícito. Cada corrección tiene un test que la documenta. |

## Fases

| Fase | Contenido | Criterio de "hecho" |
|---|---|---|
| **F0** Infraestructura ✅ | Estructura del repo, `RiverCore` con geometría base, app mínima con autocomprobación, `ios.yml` (tests + simulador + IPA) | La app abre en el iPhone 14 Pro con la autocomprobación en verde (17-11-2026) |
| **F1** Formato y referencias | `RiverCore/Project`: JSON con orden de claves, enteros y `NaN`; `settings.json`, `xsections.json`, `transformation_matrix.json`, `piv_results.json`, batimetría; carpeta de proyecto (abrir, crear, reubicar rutas, invalidar al cambiar la matriz, chequeo de compatibilidad). App: lista de aforos en Archivos, importar proyecto, detalle. Referencias: ejemplos de RIVeR en `ci/testdata/river-examples` | Un proyecto de escritorio se abre en la app y se reescribe sin cambios de significado. `tools/generate_golden.py` pasa a F2/F3, cuando haya cálculos que comparar |
| **F2** Geometría completa ✅ | Oblicua con 4 puntos + 6 distancias (trilateración), rectificación 3D con cámara (DLT de 11 parámetros), ROI, rectificación de imagen | Errores de reproyección iguales a escritorio en los ejemplos |
| **F3** PIV | Lectura de frames (AVAssetReader en la app, buffers al núcleo), correlación FFT multipasada, subpíxel gaussiano en coma flotante, filtros | Desplazamiento sintético conocido recuperado con error < 0,05 px; campos de ejemplo dentro de tolerancia |
| **F4** Sección y caudal ✅ | Batimetría (CSV), estaciones, alpha, método de sección media, área/ancho con cruces del nivel de agua, incertidumbre coherente | Caudal de los ejemplos igual a la referencia corregida |
| **F5** App MVP (LSPIV) | Flujo completo: video (importar o grabar con fps fijos, foco/exposición bloqueados y **estabilización desactivada**), frames, puntos de control, ROI, PIV, sección, resultados, exportar proyecto/CSV/PDF | Aforo real en campo con el iPhone, coherente con escritorio |
| **F6** STIV | Imágenes espacio-temporales + CNN exportada a Core ML | Ángulos y velocidades como escritorio en los ejemplos |
| **F7** Estabilización y lente | Estabilización de frames; intrínsecos del iPhone desde `AVCameraCalibrationData` | — |
| **F8** iWave (opcional) | Ajuste espectral (SADE) | — |
| **F9** Android | `android/` con Kotlin + Compose + CameraX | Mismos casos de `ci/testdata` en verde |

## Errores de RIVeR corregidos

Hallazgos de la revisión de RIVeR 3.8.3 (commit `ea65612`). Estado en el port:

| # | Defecto en escritorio | Archivo de escritorio | Estado en RIVEM |
|---|---|---|---|
| 1 | Transformación UAV de 2 puntos espejada salvo segmento Este–Oeste | `coordinate_transform.py:677-699` | **Corregido** en `UAVTransform` (F0), test `testNorthPointingSegmentIsNotMirrored` |
| 2 | Extracción de frames con desfase de uno y errores de los hilos ignorados | `video_to_frames.py:106-145` | **Corregido** en `FrameExtractor` (app): lectura secuencial frame a frame; un error detiene la extracción |
| 3 | `multipass=False` ignorado | `piv_fftmulti.py:158-175` | **Corregido** (F3), test en `PIVTests` |
| 4 | Ajuste subpíxel sobre correlación en `uint8` (peak-locking, `log(0)`) | `piv_fftmulti.py:705-774` | **Corregido** (F3): correlación en `Float`, picos en el borde → NaN |
| 5 | Un par PIV fallido aborta todo | `piv_pipeline.py:202-224` | **Corregido** (F3): el par queda en NaN y se lista en `failedPairs` |
| 6 | `left_station` se anula algebraicamente | `compute_section.py:471-475` | **No es un defecto**: la GUI ya desplaza `east_l` en `left_station` (`useSectionSlice.ts`), así que Python debe anularlo |
| 7 | `total_q_std` inconsistente con interpolación o estaciones desmarcadas | `compute_section.py:1598-1607` | **Corregido** (F4): misma base de estaciones que `total_Q`, nunca negativo |
| 8 | Área y ancho ignoran los cruces con el nivel del agua | `compute_section.py:1196-1211` | **Corregido** (F4): pisco, ancho 21,15 → 22,66 m (Q no cambia) |
| 9 | Cachés con claves incompletas (resultados obsoletos) | `orchestrator.py:35-47`, `compute_section.py:29-44` | **Corregido**: el port no usa cachés, siempre recalcula |
| 10 | `rmtree` con nombre de sección sin sanear | `stiv_pipeline.py:640` | F6: nombres saneados, borrado solo dentro del proyecto |
| 11 | Frames ilegibles sustituidos por negro/ceros sin aviso | varios | **Corregido** en PIV (F3): par en NaN y avisado |
| 12 | `xsections.json` en latin1 o UTF-8 según el manejador (Windows) | GUI escritorio | **Corregido** (F1): la app escribe ASCII con `\u` (igual en ambas lecturas) y lee ambos |
| 13 | PIV pasada 2: `normalize_to_uint8` deja ventanas sin máximo 255 y `process_result_conv`/`subpixgauss` corren los vectores a la ventana vecina (200 de 437 ventanas en el caso con fondo de canuelas) | `piv_fftmulti.py` | **Corregido** (F3): cada ventana usa su propio máximo en `Float`. La referencia de CI neutraliza el defecto (`normalization_fix`) y guarda también la salida original |
| 14 | Oblicua con anclas UTM en `float32` (pierde ~0,5 m) | `coordinate_transform.py:798-943` | **Corregido** (F2): todo en `Double` |
| 15 | Relleno superior de la imagen UAV usa el relleno horizontal | `coordinate_transform.py` | **Corregido** (F2); sin efecto con el relleno 0 de la GUI |
| 16 | Altura de ROI recomendada usa el `rw_length` de la última sección, no el mínimo | `define_roi_masks.py` | **Corregido** (F2); igual con una sola sección |
| 17 | Solución de cámara 3D: DLT sin normalizar sobre UTM (mal condicionada) | `coordinate_transform.py:316` | Mismo objetivo, resuelto con SVD de Jacobi (más precisa); tolerancias de referencia 0,1 px / 5 cm |

Cuando una corrección cambia resultados respecto a escritorio, la referencia de `ci/testdata` lo anota.

## Depuración sin Mac

- El núcleo se depura con sus pruebas (CI, o `swift test` en Windows con el toolchain de Swift).
- CI: resultados de XCTest (`.xcresult`), capturas del simulador y log de la app como artefactos.
- En el iPhone: la app escribirá un log exportable desde un panel de diagnóstico (F5). El simulador no tiene
  cámara: la captura se prueba en el teléfono.
