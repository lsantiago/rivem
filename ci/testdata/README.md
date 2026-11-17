# Datos de prueba compartidos

Casos que validan el núcleo de iOS y, más adelante, el de Android contra resultados de referencia.

- Las referencias se generan con `tools/generate_golden.py` ejecutando RIVeR escritorio (`river-cli`, fijado a
  3.8.3) sobre los ejemplos públicos de RIVeR (`examples/`: pisco, juncal, yanuncay), **con los errores conocidos
  corregidos** o con la diferencia esperada anotada junto a cada caso.
- Solo se versionan archivos pequeños (JSON/CSV y algunos frames). Videos grandes: se descargan en CI.
