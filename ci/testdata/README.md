# Datos de prueba compartidos

Casos que validan el núcleo de iOS y, más adelante, el de Android contra resultados de referencia.

- Las referencias se generan con `tools/generate_golden.py` ejecutando RIVeR escritorio (`river-cli`, fijado a
  3.8.3) sobre los ejemplos públicos de RIVeR (`examples/`: pisco, juncal, yanuncay), **con los errores conocidos
  corregidos** o con la diferencia esperada anotada junto a cada caso.
- Solo se versionan archivos pequeños (JSON/CSV y algunos frames). Videos grandes: se descargan en CI.

## Contenido

- `river-examples/`: archivos de los ejemplos de RIVeR (origen en `river-examples/SOURCE.md`).
- `desktop-projects/oblique-pisco/`: proyecto en formato de escritorio armado a mano para probar la lectura
  (ver su `README.md`). El CI lo copia a la app en el simulador y comprueba que se abre sin modificarlo.
