# RIVEM (iOS)

App nativa SwiftUI (iOS 17+). Todo el cálculo corre en el dispositivo.

- **`Sources/RiverCore`**: port del núcleo de RIVeR (`river/core/*.py`). Solo usa Foundation: sin UIKit, SwiftUI,
  AVFoundation ni Accelerate, para que se pueda probar con `swift test` en macOS, Linux o Windows y portar a Kotlin.
  - `Geometry/`: transformación UAV de 2 puntos (corregida), homografía DLT normalizada, errores de reproyección.
  - `Project/`: formato de proyecto de RIVeR escritorio 3.8.3 (`../docs/desktop-format.md`). JSON propio que
    conserva el orden de claves, los enteros y los `NaN` de Python; escribe ASCII con `\uXXXX` para que escritorio
    lo lea igual en UTF-8 o Latin-1; las rutas guardadas se resuelven por nombre de archivo dentro del proyecto.
- Los proyectos viven en `Documents/RIVeR/<nombre>/<YYYYMMDDTHHMM>/`, visibles en la app Archivos
  (En mi iPhone › RIVEM › RIVeR): se copian tal cual a la carpeta `RIVeR` de escritorio y al revés.
- **`Sources/RIVEM`**: la app (pantallas, cámara, archivos de proyecto).
- **`Tests/RiverCoreTests`**: pruebas con solución analítica y, desde F1, contra las referencias de `../ci/testdata`.

## Compilar

- **CI (recomendado):** `../.github/workflows/ios.yml` genera el proyecto con XcodeGen, ejecuta las pruebas en el
  simulador, hace una autocomprobación en la app (`ci/simulator-tests.sh`, capturas en el artefacto
  `ios-simulator-screenshots-and-logs`) y produce `RIVEM-unsigned.ipa` para instalar con iloader.
- **Solo el núcleo en Windows:** con el [toolchain de Swift para Windows](https://www.swift.org/install/windows/),
  `swift test` desde esta carpeta compila `RiverCore` y sus pruebas (la app se omite en Windows).
- **xtool (Linux/WSL o Mac):** `xtool dev` compila, firma e instala en el iPhone conectado. No probado aún.
