# RIVEM

Port móvil nativo de [RIVeR](https://github.com/oruscam/RIVeR) (ORUS), la herramienta de velocimetría de superficie
(LSPIV, STIV, iWave) para medir caudal de ríos a partir de video. Todo se procesa en el teléfono.

- **iOS** (`ios/`): Swift + SwiftUI, iOS 17 o superior. Probado en iPhone 14 Pro. En desarrollo.
- **Android** (`android/`): pendiente; reutilizará los mismos casos de prueba.
- **Compatibilidad con RIVeR escritorio 3.8.3:** un aforo hecho en el teléfono se abre en RIVeR escritorio y al
  revés (mismo formato de carpeta de proyecto, ver [docs/desktop-format.md](docs/desktop-format.md)).
- **Errores de RIVeR corregidos:** el port no copia los defectos encontrados en la revisión del código de escritorio
  (lista y estado en [docs/PLAN.md](docs/PLAN.md#errores-de-river-corregidos)).

## Obtener la app sin Mac

GitHub Actions compila en un runner macOS (`.github/workflows/ios.yml`):

1. Haz push a GitHub, abre la ejecución en la pestaña **Actions** y descarga el artefacto `RIVEM-unsigned-ipa`.
   Con un tag (`git tag v0.1.0 && git push origin v0.1.0`) el `.ipa` también queda adjunto a una Release.
2. Descomprime el zip para obtener `RIVEM-unsigned.ipa`.
3. Instálalo desde Windows con [iloader](https://github.com/nab138/iloader/releases) (Apple ID + iPhone por USB).
   Con un Apple ID gratuito la app caduca a los 7 días y hay que reinstalarla.
4. En el iPhone: **Ajustes → Privacidad y seguridad → Modo de desarrollador** (iOS 16+), y luego
   **Ajustes → General → VPN y gestión de dispositivos** para confiar en tu Apple ID.

## Estructura

| Carpeta | Contenido |
|---|---|
| `ios/` | Paquete SwiftPM: `Sources/RiverCore` (núcleo de cálculo, solo Foundation), `Sources/RIVEM` (app SwiftUI), `Tests/`, `project.yml` (XcodeGen), `xtool.yml`, `ci/` |
| `android/` | App Android (pendiente) |
| `ci/testdata/` | Datos y resultados de referencia compartidos por iOS y Android |
| `tools/` | Scripts de apoyo (generar referencias con RIVeR escritorio, iconos) |
| `docs/` | Plan, formato de proyecto de escritorio, decisiones |
| `.github/workflows/` | Un workflow por plataforma (`ios.yml`, luego `android.yml`) |

## Licencia

AGPL-3.0, la misma que RIVeR, porque es una obra derivada (ver `LICENSE`). RIVeR: © ORUS / Antoine Patalano et al.
Si publicas resultados obtenidos con esta app, cita RIVeR: Patalano, A., García, C. M., Rodríguez, A. (2017),
*Rectification of Image Velocity Results (RIVeR)*, Computers & Geosciences, doi:10.1016/j.cageo.2017.07.009.
