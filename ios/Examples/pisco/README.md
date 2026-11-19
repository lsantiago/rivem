# Ejemplo río Pisco (RIVeR)

Datos del ejemplo `pisco` de [oruscam/RIVeR](https://github.com/oruscam/RIVeR) 3.8.3 (commit `ea65612`,
AGPL-3.0, © ORUS / Antoine Patalano et al.), incluidos en la app para el botón «Instalar ejemplo»
(`Sources/RIVEM/Pipeline/ExampleProject.swift`):

- `pisco_section.csv` ← `examples/data/bathymetry/pisco_section.csv`
- `frames/` ← `examples/data/frames/pisco` (30 frames, uno de cada 2, 1920x1080). No se versionan: los descarga
  `ci/fetch-example.sh` antes de compilar (unos 16 MB).
