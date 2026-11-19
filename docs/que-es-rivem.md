# ¿Para qué sirve RIVEM?

RIVEM sirve para **medir el caudal de un río (cuánta agua pasa por segundo, en m³/s) a partir de un video**,
directamente en el iPhone.

## Cómo funciona

1. **Se graba el río** con el iPhone o con un dron. En la superficie tiene que haber algo que se mueva con la
   corriente: espuma, hojas, ondas o textura.
2. **La app sigue ese movimiento entre los frames** con una técnica llamada LSPIV (velocimetría por imágenes de
   partículas a gran escala). Compara fotogramas consecutivos y calcula cuántos píxeles se movió cada zona del agua.
3. **Convierte píxeles en metros** con la georreferenciación: los puntos y distancias que mides en campo. Así obtiene
   la velocidad real de la superficie en m/s.
4. **Combina esa velocidad con la forma del cauce.** Con la batimetría (la profundidad de la sección) y el nivel del
   agua calcula el área mojada y, con ella, el **caudal**.

## Para qué se usa

- **Aforos sin meterse al río.** Es más seguro en crecidas o con corrientes fuertes, cuando no se puede usar un
  molinete o un ADCP.
- **Ríos de montaña o de difícil acceso**, donde llevar equipo es complicado.
- **Monitoreo hidrológico:** curvas nivel–caudal, crecidas, gestión de cuencas y estudios ambientales.
- **Bajo costo:** solo hace falta un celular o un dron, más las mediciones topográficas del sitio.

## Qué es RIVEM respecto a RIVeR

- **RIVeR** es el programa de escritorio (Windows/Mac/Linux) que hace esto. RIVEM es su versión para iPhone.
- **Hace el cálculo en el sitio**, sin computadora ni internet.
- **Sus proyectos abren en RIVeR escritorio** y al revés, para revisar o reprocesar en la oficina.
- **Corrige varios errores de RIVeR** encontrados al portarlo; están listados en la tabla «Errores de RIVeR
  corregidos» de [PLAN.md](PLAN.md).

## En resumen

Grabas el río, marcas unos puntos de referencia, cargas la sección del cauce y la app te dice cuánta agua está
pasando.

Para preparar un aforo de validación, ver [validacion-campo.md](validacion-campo.md).
