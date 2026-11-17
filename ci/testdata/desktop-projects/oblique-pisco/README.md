# Proyecto de escritorio de prueba (oblicuo, Pisco)

Construido a mano siguiendo `docs/desktop-format.md`, con datos reales del ejemplo `pisco` de RIVeR
(`examples/results/pisco`: matriz y sección; `examples/data/bathymetry/pisco_section.csv`). No lo generó la GUI de
escritorio. Ejercita lo que la app debe tolerar:

- rutas absolutas de Windows (se resuelven por nombre de archivo dentro de la carpeta);
- claves desconocidas (`future_desktop_key`) que deben conservarse al reescribir;
- nombre de sección con tilde y token `NaN` en `xsections.json`, y la clave `summary`;
- `num_stations` entero.

Los puntos 3 y 4 de `control_points` y las distancias son ilustrativos. La carpeta `frames/` la crean las pruebas
(git no guarda carpetas vacías).
