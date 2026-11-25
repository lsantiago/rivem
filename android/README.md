# RIVEM (Android)

Kotlin + Jetpack Compose, minSdk 28. Mismo flujo y mismo formato de proyecto que la app iOS y RIVeR escritorio 3.8.3.

- `core/`: port a Kotlin de `ios/Sources/RiverCore` (JVM puro): geometría, imagen, formato de proyecto, PIV,
  sección y caudal, calidad/incertidumbre, curva nivel–caudal. Mismos tests que Swift sobre `ci/testdata`.
  Local sin SDK: `JUNIT_DIR=<carpeta con junit-4.13.2.jar y hamcrest-core-1.3.jar> bash core/test-local.sh`.
- `app/`: la app. Aforos en `Android/data/org.rivem.app/files/RIVeR/<nombre>/<fecha>/` (se copian por USB al
  espacio de trabajo de RIVeR escritorio). Distancias con AR usan ARCore (opcional).
- CI (`.github/workflows/android.yml`): tests del núcleo, APK de depuración y, en un emulador, el ejemplo pisco
  completo (`ci/emulator-tests.sh`) con capturas de cada paso.
