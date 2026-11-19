# Formato de proyecto de RIVeR Desktop (v3.8.3) para interoperar con la app iOS

Especificacion tecnica del formato en disco que escribe y lee la app de escritorio `oruscam/RIVeR` v3.8.3
(`gui/package.json:5`). Objetivo: que una app nativa iOS (Swift) cree proyectos que el escritorio abra y
abra proyectos creados por el escritorio.

Fuente analizada: repo clonado de RIVeR 3.8.3. Todas las citas son `ruta:linea` relativas a la raiz del repo.
Abreviaturas de rutas:

- `H/`  = `gui/electron/ipcMainHandlers/`
- `HU/` = `gui/electron/ipcMainHandlers/utils/`
- `C/`  = `gui/commons/`
- `S/`  = `gui/src/`
- `PY/` = `river/core/`
- `CLI/` = `river/cli/commands/`

Convenciones de este documento: "no verificado" / "ambiguo" marca lo que el codigo no permite afirmar.
Los ejemplos de `examples/results/*` provienen de los notebooks de la libreria Python, NO de proyectos de la GUI:
sirven para confirmar tipos y orden de matrices, pero no contienen `settings.json` ni el formato completo de
`xsections.json` (ver seccion 2.9).

---------------------------------------------------------------------------------------------------

## 1. Estructura de carpetas

### 1.1 Ubicacion y nombre

- Espacio de trabajo: `<carpeta elegida>/RIVeR` (`gui/electron/riverConfig.ts:30-39`; por defecto `<userDir>/RIVeR`,
  `gui/electron/main.ts:135`). Configuracion global fuera del proyecto: `river-config.json` en `userData`
  (`gui/electron/riverConfig.ts:9`); no forma parte del proyecto.
- Al crear un proyecto: `<workspace>/<nombreProyecto>/<YYYYMMDDTHHMM>/`.
  - `nombreProyecto` = nombre del video sin extension, cortado en el primer punto (`name.split('.')[0]`,
    `H/initProject.ts:47`); para un proyecto de carpeta de frames se usa el nombre completo (`H/initProject.ts:47`).
  - Carpeta con fecha: hora local, formato `YYYYMMDDTHHMM` con ceros a la izquierda (`H/createFolderStructure.ts:118-127`).
    Si ya existe, el escritorio pregunta y la borra recursivamente (`H/createFolderStructure.ts:20-52`).
- Lo que se "abre" es la carpeta con fecha (la que contiene `settings.json`). `load-project` acepta cualquier carpeta
  que contenga `settings.json`; el nombre de la carpeta no se valida (`H/loadProject.ts:115-121`,
  `H/utils/resolveProjectFolder.ts:7-15`, `H/projectsExist.ts:10-20`). El formato de fecha es solo convencion.

### 1.2 Arbol y paso que crea cada archivo

```
<YYYYMMDDTHHMM>/
  settings.json                  Paso 0 (crear proyecto)      H/createFolderStructure.ts:79-108
  frames/                        Paso 0 crea vacia; Paso 1 (Video Range) la llena   H/createFolderStructure.ts:70-77, HU/extractFrames.ts
    0000000000.jpg ...           Paso 1 (video-to-frames / frames-to-frames)        PY/video_to_frames.py:157
    background.jpg               opcional; cache del filtro "remove background" (la GUI lo ignora al listar)  H/loadProject.ts:35
  transformation_matrix.json     Paso 2 (UAV: pixel size / oblique: control points / ipcam: nivel)   HU/createMatrix.ts:37-40
  transformed_image.png          Paso 2 (UAV, oblique): ortoimagen RGBA          CLI/coordinate_transform.py:83-88
  ortho_image.png                Paso 2 (ipcam): ortoimagen del 3D               CLI/coordinate_transform.py:300-305
  grp_3d.json                    Paso 2 ipcam                                      H/calculate3dRectification.ts:95-96
  full_grp_3d.json               Paso 2 ipcam, solo si hay puntos no usados        H/calculate3dRectification.ts:18,97-99
  camera_solution_3d.json        Paso 2 ipcam                                      H/calculate3dRectification.ts:122-124
  xsections.json                 Paso 3 (Cross Sections), luego Paso 5 lo enriquece H/setSections.ts:18-33
  <bathymetry>.csv               Paso 3: copia normalizada dentro del proyecto     H/getBathimetry.ts:157-188
  mask.npy, mask.png, bbox.json  Paso 3 (create-mask-and-bbox)                     CLI/define_roi_masks.py:118-131
  usr_mask_<N>.npy               Paso 3, solo mascaras de usuario                  CLI/define_roi_masks.py:205
  final_mask.npy, final_mask.png Paso 3, ROI + mascaras de usuario (compile-masks) CLI/define_roi_masks.py:306,340-343
  stabilization_regions.json     Paso 1, solo si se estabiliza                     HU/extractFrames.ts:43,93-96,133
  sanity_check.jpg               Paso 1, solo si se estabiliza                     CLI/video_to_frames.py:47
  piv_results.json               Paso 4 (analyze-all)                              PY/orchestrator.py:99,119
  _stats_cache.json              Pasos 4/5 (cache de estadisticas, sidecar)        PY/orchestrator.py:125,134; CLI/compute_section.py:55,76
  stis/<seccion>/sti_<id>.png    Paso 4 (STIV)                                     PY/orchestrator.py:142,159; PY/stiv_pipeline.py:646
  iwave_spectra/<seccion>/spectrum_<id>.png + spectra.json   Paso 4 (iWave)       PY/orchestrator.py:179,198; PY/iwave_pipeline.py:599-601
  rectified/000000.jpg ...       Paso 4 (iWave, frames ortorectificados)           PY/orchestrator.py:180-183; PY/iwave_pipeline.py:189
  river.log                      Cada llamada al CLI Python agrega un bloque JSON  HU/executeRiverCli.ts:153-193
  gif-frames/, output.mp4        Exportar GIF/video                                H/getGif.ts:42,118
  report.html                    Exportar reporte (ubicacion por defecto)          H/saveReportHtml.ts:15
```

Todo lo marcado "Paso 4/5" y las carpetas `stis`, `iwave_spectra`, `rectified`, `gif-frames`, `river.log`,
`report.html`, `sanity_check.jpg`, `_stats_cache.json` son opcionales para que el escritorio abra el proyecto; ver
requisitos de carga en 2.1.

Fuera del proyecto (pero referenciados): el video original (`video.filepath`), la carpeta de imagenes del 3D
(`rectification_3d_images`), el perfil de lente (`lens_correction`, normalmente
`<workspace>/calibration_profiles/<camara>/<lente>/profile.json`, `H/cameraCalibration.ts:270-293`).

### 1.3 Comportamiento al mover el proyecto

Ver seccion 6.2.

---------------------------------------------------------------------------------------------------

## 2. Archivos JSON: claves, tipos y unidades

### 2.0 Convenciones globales

- Sistema de unidades: TODO lo almacenado en disco esta en SI (metros, m/s, m3/s). `unit_system` es solo
  preferencia de visualizacion; ver 2.1 y comentarios de conversion en `S/hooks/useSectionSlice.ts:73-75,236-239`
  ("the store must stay in SI"), `S/hooks/useObliqueSlice.ts:195-199,295`, `S/hooks/useUavSlice.ts:152,189,203`.
  Los importadores convierten pie->metro (x 0.3048) antes de guardar: `H/getBathimetry.ts:21,117-121`,
  `H/getDistances.ts:23,54-58`, `H/getPoints.ts:23,88-90`. Constante: `S/constants/constants.ts:147-151`.
- Coordenadas de pixel: sistema de imagen de los frames extraidos (`frames/*.jpg`), origen arriba-izquierda,
  x hacia la derecha, y hacia abajo (consistente con `cv2.imread` y `np.mask[y, x]`:
  `PY/coordinate_transform.py:707-711,946-967`, `PY/define_roi_masks.py:98-106,497-503`). Si se extrae con
  `factor < 1`, los frames estan reducidos (`PY/video_to_frames.py:116-119,138`) y las coordenadas de pixel de
  matriz/secciones/mascara/PIV estan en ese espacio reducido, porque se calculan sobre `firstFrame`
  (`H/setPixelSize.ts:31-35`, `CLI/define_roi_masks.py:107-116`, `PY/orchestrator.py:62-75`).
  `video.resolution` guarda en cambio la resolucion original del video (`H/createFolderStructure.ts:100-103`).
  Que la GUI reescale una matriz ya calculada si luego cambia `factor`: no verificado.
- Coordenadas del mundo real: `x = este (east)`, `y = norte (north)`, en metros; marco local arbitrario
  (UAV y oblicuo parten de (0,0); ipcam usa las coordenadas X,Y,Z de los GRP del usuario, p.ej. UTM,
  `examples/data/grps/full_grp_3d.json`). Z (cota) en ipcam es vertical hacia arriba.
- Matrices: `transformation_matrix.json` es una lista de 3 listas de 3 numeros, orden por filas (row-major);
  mapea PIXEL -> MUNDO REAL en coordenadas homogeneas: `[E,N,w]^T = M * [x,y,1]^T`, luego `E/w, N/w`
  (`PY/coordinate_transform.py:946-967`, `C/coordinates.ts:65-76`). No esta normalizada (M[2][2] puede ser != 1:
  `examples/results/pisco/transformation.json` tiene -0.348...). Se escribe con `JSON.stringify` sin indentacion
  (`HU/createMatrix.ts:40`). El CLI exige 9 elementos (`CLI/coordinate_transform.py:16,213`).
- NaN / Infinity: Python escribe tokens `NaN`/`Infinity` sin comillas (no es JSON estricto). Node los sanea a `null`
  antes de parsear (`HU/sanitizeJson.ts:8-12`, `HU/readResultsPiv.ts:6`, `H/loadProject.ts:22`). Un lector en Swift
  debe tolerarlos (JSONSerialization no los acepta por defecto; hay que preprocesar, igual que `sanitizeNonStandardJsonTokens`).
  Un escritor en Swift debe usar `NaN` (no `null`) en `piv_results.json` si espera que Python lo reabra
  (`PY/compute_section.py:1055-1057` hace `np.array(...).reshape` sobre `u_median`; con `null` sale dtype object: riesgo no
  verificado); en `xsections.json` los `null` son tolerados en estadisticas (`PY/compute_section.py:1586`).
- Indentacion al escribir: 4 espacios en la mayoria de handlers, 2 en otros; irrelevante para la lectura.
- Codificacion: ver seccion 6.1. No escribir BOM (Node `JSON.parse` falla con BOM porque `readFile(...,'utf-8')` no lo quita).

### 2.1 `settings.json` (obligatorio)

Lo crea `H/createFolderStructure.ts:79-108` y lo van ampliando los pasos siguientes. Escritura UTF-8.

| Clave | Tipo | Escribe | Notas |
|---|---|---|---|
| `creation_date` | string `YYYYMMDDTHHMM` | `H/createFolderStructure.ts:81` | hora local; igual al nombre de la carpeta |
| `footage` | string: `"uav"`, `"oblique"`, `"ipcam"` | `:82` | `ipcam` = rectificacion 3D (ver seccion 3). Tipos validos: `H/interfaces.ts:111`, `S/constants/constants.ts:6-8` |
| `language` | string (`"en"`, ...) | `:83` | la GUI al cargar usa `localStorage`, no esta clave (`S/hooks/useProjectSlice.ts:456`) |
| `unit_system` | `"si"` o `"imperial"` | `:84`; `H/setProjectMetadata.ts:17` | solo visualizacion; cualquier otro valor se trata como SI (`=== 'imperial'`) |
| `video` | objeto | `:85-104` | ver abajo |
| `video_range` | `{start:int, end:int, step:int, factor:number}` | `HU/extractFrames.ts:123-128` | `start` = indice de frame fuente 0-based; `end` = indice fuente, exclusivo en la extraccion; `step` = cada cuantos frames; `factor` in (0,1] (`PY/video_to_frames.py:182-183`) |
| `lens_correction` | string (ruta absoluta a `profile.json`) o `null` | `HU/extractFrames.ts:130` | fuera del proyecto |
| `stabilization` | `true` o `null` | `:134,137` | |
| `stabilization_regions_path` | string o `null` | `:135,138` | propia del proyecto (se reubica) |
| `pixel_size` | objeto (solo UAV) | `H/setPixelSize.ts:16-27` | `{size, rw_length, x1,y1,x2,y2, east1,north1,east2,north2}`; `size` m/px; `rw_length` m; (x1,y1),(x2,y2) pixeles; east/north en metros (por defecto (0,0) y (rw_length,0): `S/hooks/useUavSlice.ts:176-180,214-221`) |
| `control_points` | objeto (solo oblique) | `H/setControlPoints.ts:15-34` | `{coordinates:{x1,y1,..,x4,y4} (pixel), distances:{d12,d23,d34,d41,d13,d24} (m)}`. Las anclas este/norte NO se guardan; la GUI las recalcula con la matriz (`S/helpers/loadProjectHelpers.ts:135-138`) |
| `transformation` | objeto | `H/setPixelSize.ts:14,50-55`; `H/setControlPoints.ts:13,90-95`; `H/saveTransformationMatrix.ts:18-24` | `{matrix:string(ruta), resolution:number, extent:[xmin,xmax,ymin,ymax], roi:[x,y,w,h] (solo oblique)}`. En ipcam solo `matrix`. Ver 2.4 |
| `xsections` | string (ruta absoluta a `xsections.json`) | `H/setSections.ts:26` | propia del proyecto |
| `piv_results` | string (ruta absoluta a `piv_results.json`) | `H/getQuiver.ts:76` | solo se usa como bandera "hay resultados"; el archivo leido es siempre `<proyecto>/piv_results.json` (`HU/pivResultsPath.ts:8-10`) |
| `processing` | objeto | `H/getQuiver.ts:115-131` | `{interrogation_area_1, interrogation_area_2, roi_height, remove_background, clahe, clip_limit, std_filtering, std_threshold, median_test_filtering, median_test_epsilon, median_test_threshold, stiv:bool, iwave:bool, iwave_manual: null | {flow_type:"waves"|"no-waves", window_size:number>0, smax:number in (0,10]}}`. El lector tambien admite `artificial_seeding`, `grayscale` heredados (`S/helpers/loadProjectHelpers.ts:350-368`) |
| `user_masks` | lista de mascaras; cada mascara lista de `{x,y,id}` (pixel) | `HU/saveUserMasks.ts:5-26` | >= 3 puntos por mascara (`CLI/define_roi_masks.py:200-201`) |
| `colorbar_limits` | `{min,max}` | `H/setColorbarLimits.ts:13-16` | `null` en ambos = por defecto (`S/hooks/useProjectSlice.ts:493-501`). Unidad no verificada |
| `rectification_3d_images` | string (carpeta absoluta) | `H/getIpcamImages.ts:48` | ipcam; fuera del proyecto |
| `grp_3d` | string (ruta absoluta a `grp_3d.json`) | `H/calculate3dRectification.ts:126` | ipcam; propia del proyecto |
| `camera_solution_3d` | string (ruta absoluta) | `:127` | ipcam; propia del proyecto |
| `river_name`, `site` | string | `H/setProjectMetadata.ts:15-16` | metadatos al terminar |
| `medition_date` | string `DD/MM/YYYY HH:mm` | `H/setProjectMetadata.ts:18`; formato en `HU/saveProjectMetadata.ts:78` | fecha de medicion |
| `metadata` | objeto | `HU/saveProjectMetadata.ts:67-74` | `{identifier:"<rio>-<sitio>-discharge-<YYYYMMDDTHHMM>" (minusculas), temporal:{start,end (ISO UTC), local_timestamp, time_zone, utc_offset ("+HH:00")}, instrument_type:"LSPIV", observation_type:"discharge", data_quality:"provisional", license:"CC-BY 4.0"}` (perfil WCMP2, `CHANGELOG.md:260`). Opcional |

Bloque `video` (video normal), `H/createFolderStructure.ts:94-104`:

```json
"video": {
  "filepath": "/ruta/abs/video.mp4",   // string; OBLIGATORIO (ver abajo)
  "name": "video.mp4",
  "total_length": 21.5215,              // segundos (de ffprobe stream.duration; tipo number/string no verificado)
  "time_between_frames": 0.03,          // = round(1/fps, 2 decimales)  (H/createFolderStructure.ts:98)
  "fps": 30,                            // ENTERO: Math.round(num/den) de r_frame_rate (HU/getVideoMetadata.ts:66-67)
  "resolution": { "width": 4096, "height": 2160 }   // tras aplicar rotacion 90/-90 (HU/getVideoMetadata.ts:50-58)
}
```
(valores de ejemplo reales de `HU/videoFallback.test.ts:5-12`).

Bloque `video` de un proyecto "carpeta de frames" (`C/framesSource.ts:4-14,75-95`): ademas lleva
`"source": "frames"`, `"frame_count": N`; `filepath` = carpeta origen; `name` = nombre de la carpeta; `fps = 1000/intervalMs`;
`time_between_frames = round9(intervalMs/1000)`; `total_length = round6(frame_count*intervalMs/1000)`.
Un video normal NO lleva `source` (`HU/videoFallback.ts:12`; se interpreta como `'video'`).

Claves REQUERIDAS para que `load-project` tenga exito (probadas contra el codigo):

1. Carpeta con `settings.json` (si no: error `noSettingsFile`, `H/loadProject.ts:120-121`), JSON valido (`HU/prepareProject.ts:18`).
2. `settings.video` objeto con `filepath` string. Si falta, `path.dirname(undefined)` lanza TypeError y la carga falla
   (`H/loadProject.ts:68-69`). `video.source` se lee en `:153`.
3. Carpeta `frames/` presente (aunque vacia). `readdir` sin `try` aborta la carga con ENOENT (`H/loadProject.ts:30,128`).
4. Si `settings.xsections` esta definido: el archivo debe existir; si no, `transformData(undefined)` lanza al leer
   `data.summary` (`HU/transformCrossSectionsData.ts:32`, invocado en `H/loadProject.ts:135-137`) y la carga falla.
   `prepareProject` solo corrige `bath`, no crea el archivo (`HU/prepareProject.ts:24-30`).
5. Si `settings.grp_3d` o `settings.camera_solution_3d` estan definidos: el archivo debe existir (si no, `JSON.parse(undefined)`
   lanza: `H/loadProject.ts:141-146`, `HU/parseGrp3dPoints.ts:2`, `HU/parsedCameraSolution.ts:2`).
6. En el renderer (errores aqui devuelven `'notValidDirectory'`, `S/hooks/useProjectSlice.ts:755-759`):
   - `settings.footage` (`:446`), `video.width/height/fps/duration/name` (de `settings.video` si falta el video, `HU/videoFallback.ts:19-33`).
   - Si hay `piv_results` o `xsections` o `pixel_size` o `control_points`: `settings.video_range` con `step,start,end,factor`
     (`S/hooks/useProjectSlice.ts:559,668,702,722`; desestructuracion en `S/helpers/loadProjectHelpers.ts:33`).
   - Si hay `piv_results` (rama de resultados, `:521-631`): ademas `xsections` valido (`Object.entries(undefined)` lanza,
     `S/helpers/loadProjectHelpers.ts:198`) y `piv_results.json` legible con `x,y,u,v,u_median,v_median`.
   - Si hay `xsections`: cada `bath` se re-importa con `get-bathimetry` (`S/helpers/loadProjectHelpers.ts:237`); un `bath`
     inexistente no aborta (se reporta como `missingInputs`, ver 6.2).
7. Si `settings.pixel_size`/`control_points` existen sin matriz en disco, la GUI intenta `transformPixelToRealWorld(..., undefined)`
   y falla: escribir siempre `transformation_matrix.json` y `transformation.matrix` junto con ellos.

Resultado de `load-project`: `piv_results` solo si `settings.piv_results` es truthy (`HU/pivResultsPath.ts:9`) y el archivo es legible
(`.catch(() => undefined)`, `H/loadProject.ts:139`).

Ejemplo minimo valido (abre en "Video Range"; sin matriz ni secciones):

```json
{
  "creation_date": "20260115T0930",
  "footage": "uav",
  "language": "en",
  "unit_system": "si",
  "video": {
    "filepath": "/ruta/que/puede/no/existir/video.mp4",
    "name": "video.mp4",
    "total_length": 21.52,
    "time_between_frames": 0.03,
    "fps": 30,
    "resolution": { "width": 1920, "height": 1080 }
  }
}
```
(+ carpeta `frames/` vacia o con frames). Si `filepath` no existe, el escritorio abre igual y marca `videoMissing`
(`HU/prepareProject.ts:52-55`, `H/loadProject.ts:149-155`); permite "Locate" (6.2).

Ejemplo UAV con rango de video, tamano de pixel y matriz (abre en "Cross Sections"):

```json
{
  "creation_date": "20260115T0930", "footage": "uav", "language": "en", "unit_system": "si",
  "video": { "filepath": "/x/canuelas.mp4", "name": "canuelas.mp4", "total_length": 3.0,
             "time_between_frames": 0.03, "fps": 30, "resolution": {"width":1920,"height":1080} },
  "video_range": { "start": 0, "end": 90, "step": 2, "factor": 1 },
  "lens_correction": null, "stabilization": null, "stabilization_regions_path": null,
  "pixel_size": { "size": 0.0194, "rw_length": 20.0, "x1": 640, "y1": 540, "x2": 1670, "y2": 540,
                  "east1": 0, "north1": 0, "east2": 20.0, "north2": 0 },
  "transformation": { "matrix": "/ruta/proyecto/transformation_matrix.json",
                      "resolution": 0.0, "extent": [0,0,0,0] }
}
```
(Cuando se abre, `matrix` y todos los campos "propios" se reescriben a `<carpeta>/transformation_matrix.json`, 6.2. Los
valores numericos son ilustrativos, no tomados de un archivo real.)

### 2.2 `frames/` (imagenes)

Ver seccion 5.

### 2.3 `transformation_matrix.json`

Lista 3x3 row-major, pixel -> mundo real (2.0). Es la unica fuente de verdad de la geometria: oblique y UAV recalculan anclas desde ella.
Ejemplos reales: `examples/results/pisco/transformation.json` (oblicuo), `examples/results/yanuncay/transformation.json` (UAV: `[[s,0,tx],[0,-s,ty],[0,0,1]]`-like;
`yanuncay` tiene diagonal 0.0048283357 / -0.0048283357 y ultima fila `[0,0,1]`).

Como se obtiene en cada modo (el CLI Python hace el calculo; la app iOS debera replicarlo):

- UAV (`PY/coordinate_transform.py:626-743`, `get_uav_transformation_matrix`): dados pixeles (x1,y1),(x2,y2), mundo (E1,N1),(E2,N2),
  `s = pixel_size` (o `dist_rw/dist_pix`):
  `theta = atan2(N2-N1, E2-E1) - atan2(y2-y1, x2-x1)`;
  `R = [[c,-sn,0],[sn,c,0],[0,0,1]]`;
  `tx = E1 - (x1*s*c - y1*s*sn)`; `ty = N1 - (-x1*s*sn - y1*s*c)`;
  `M = [[s,0,tx],[0,-s,ty],[0,0,1]] @ R` (`:676-699`). Eje y de pixel hacia abajo vs. norte hacia arriba se resuelve con `-s`.
  Salida adicional si hay imagen: `extent=[E_min,E_max,N_min,N_max]` (con margen 5 %, `:202-211`) y `output_resolution` (m/px de `transformed_image.png`, `:739`).
- Oblique (`PY/coordinate_transform.py:798-943`): mundo de P1=(0,0), P2=(d12,0) (o anclas `east1,north1,east2,north2` si no son todo 0,
  `H/setControlPoints.ts:61-75`; `DISTANCE_TOLERANCE=1` y `--enforce-d12`); P3,P4 por minimos cuadrados sobre las 5 distancias restantes
  (`optimize_coordinates`, `:1039-1084`, parte de `[d12/2]*4`); `H = findHomography(mundo, pixel)`; `M = inv(H)` (`:903-908`).
  Orden de puntos 1,2,3,4 y distancias `d12,d23,d34,d41,d13,d24` (d13 y d24 son diagonales). `roi` = `[x_min, y_min, ancho, alto]` en pixeles
  del rectangulo que enmarca los 4 puntos (`:746-795,911`), `roi_padding` por defecto 0 desde la GUI (`CLI/coordinate_transform.py:27`).
- Ipcam (`HU/saveTransformationMatrix.ts`, `C/coordinates.ts:142-154`): la GUI la calcula, no el CLI. Con `P` (matriz de camara 3x4,
  `camera_solution_3d.json.camera_matrix`) y la cota `z` del nivel de agua:
  `H = [[P00,P01,z*P02+P03],[P10,P11,z*P12+P13],[P20,P21,z*P22+P23]]`; `M = inv(H)`. Se recalcula y reescribe cada vez que cambia el nivel
  (`S/hooks/useSectionSlice.ts:476-490,780-789`). Solo se guarda `transformation.matrix` (sin `resolution/extent/roi`) (`HU/saveTransformationMatrix.ts:18-24`).

Efecto colateral importante: escribir una matriz nueva borra `xsections.json`, `bbox.json`, mascara `.npy` y `piv_results.json` y quita
`settings.xsections` / `settings.piv_results` si estaban (`HU/createMatrix.ts:8-34`). Un lector que reemplace la matriz debe hacer lo mismo
para no dejar resultados incoherentes.

`transformed_image.png` (UAV/oblique): RGBA 8 bit; `flip_y=True` por defecto (filas invertidas: norte arriba) y alfa 0 fuera de imagen
(`CLI/coordinate_transform.py:30-31`, `PY/coordinate_transform.py:238-264`). Es solo visualizacion; la GUI la pide siempre que exista matriz
(`H/loadProject.ts:173`), si falta solo se ve una imagen rota. Su tamano se usa en `onLoadPixelSize` (`S/helpers/loadProjectHelpers.ts:79`):
escribir tambien este PNG o la carga de pixel_size/control_points podria fallar (`getImageSize` de una imagen inexistente; no verificado).

### 2.4 `xsections.json` (secciones transversales)

Objeto cuyas claves son los NOMBRES de seccion (p.ej. `"CS_default_1"`, `S/store/section/sectionSlice.ts:8`), en orden: el indice de seccion
que usa Python es la posicion de la clave (`PY/compute_section.py:1377-1378`). Una clave reservada `"summary"` puede aparecer al final.
El nombre se usa tambien como nombre de carpeta (`stis/<nombre>/`, `iwave_spectra/<nombre>/`: `H/getStis.ts:22`, `H/getIwaveSpectra.ts:62`):
evitar caracteres invalidos en nombres de archivo y no usar `summary`.

Parte que escribe la GUI al definir secciones (Paso 3), `S/helpers/adapterCrossSections.ts:3-40` + `H/setSections.ts:21`:

| Clave | Tipo | Significado |
|---|---|---|
| `bath` | string, ruta al CSV de batimetria | ver 2.6; se reubica al abrir (6.2) |
| `rw_length` | number (m) | ancho mojado = `x2Intersection - x1Intersection` (`S/helpers/getBathimetryValues.ts:150-152`) |
| `level` | number (m, misma cota que la batimetria) | nivel de agua; por defecto el menor de los dos extremos (`:17-21,138`). **No puede quedar por encima de las dos orillas redondeadas al cm**: al reabrir, `getBathimetryValues` no encuentra cruces y la pantalla de resultados de escritorio (3.8.4) falla (`Cannot read properties of undefined (reading 'filter')`) y queda en blanco. Ej.: pisco con `level` 1.765 (orillas 1.765 → 1.76) |
| `left_station` | number (m) | desplazamiento de la estacion izquierda sobre la linea (por defecto 0, `:165`) |
| `dir_xl,dir_yl,dir_xr,dir_yr` | number (pixel) | linea dibujada por el usuario |
| `dir_east_l,dir_north_l,dir_east_r,dir_north_r` | number (m) | esa linea en mundo real |
| `xl,yl,xr,yr` | number (pixel) | extremos de la batimetria sobre esa linea: `P0 + dir*(-left_station)` y `P0 + dir*(width - left_station)` (`S/hooks/useSectionSlice.ts:849-871`) |
| `east_l,north_l,east_r,north_r` | number (m) | `(xl,yl)`,`(xr,yr)` transformados con la matriz (`S/hooks/useSectionSlice.ts:379-391`) |
| `num_stations` | int | numero de estaciones (>= 2; `divide_segment_to_dict` divide por `num_stations-1`, `PY/compute_section.py:560`) |
| `alpha` | number | coeficiente velocidad superficial -> media (por defecto 0.85 en CLI, `CLI/compute_section.py:21`) |

Estas 22 claves son las "basicas" que conserva `clearCrossSections` al reiniciar resultados (`HU/clearCrossSections.ts:14-37`, que ademas
borra `summary`). Python lee obligatoriamente `bath, left_station, east_l, north_l, east_r, north_r, level, alpha, num_stations`
(`PY/compute_section.py:1399-1458`) y, para mascaras, `east_l,north_l,east_r,north_r` + `bath/level` (`PY/define_roi_masks.py:194-240`).
Estacion 1 = izquierda, ultima = derecha; "izquierda/derecha" se definen mirando en el sentido de `left -> right`; el sentido de corriente
positivo se deriva de esa orientacion (`PY/compute_section.py:637-675,761-791`; sin verificar el criterio exacto de signo aqui).

Parte que anade Python (Paso 4 `analyze-all` y `update-xsection`; todas las listas tienen longitud `num_stations`): `id` (1..N),
`east`, `north`, `distance` (m desde el extremo izquierdo de la zona mojada), `x`, `y` (pixel), `wet_segments` (lista de `[inicio,fin]`
en m), `displacement_x/y` (pixel, desplazamiento por par de frames), `displacement_east/north` (m por par), `streamwise_east/north`,
`crosswise_east/north`, `streamwise_x/y`, `streamwise_velocity_magnitude` (m/s = streamwise / (step/fps)), `depth` (m = level - cota),
`check` (bool), `W`, `A`, `Q`, `Q_portion`, `Q_minus_std`, `Q_plus_std`, `minus_std`, `plus_std`, `5th_percentile`, `95th_percentile`,
`seeded_vel_profile`, `filled_streamwise_velocity_magnitude` y `filled_seeded_vel_profile` (si interpolado), y escalares `total_Q`
(m3/s), `total_q_std`, `measured_Q`, `interpolated_Q` (fracciones), `total_A`, `total_W`, `max_depth`, `average_depth`, `mean_V`, `mean_Vs`
(`PY/compute_section.py:567-579,596,936-950,977,1031,1095-1106,1490-1521,1689-1732`). La GUI anade `interpolated`, `artificial_seeding`,
`showVelocityStd`, `showPercentile` (`H/getResultData.ts:72-80,141-146`) y opcionalmente `stiv_angle_manual_profile`
(`H/setStivManualAngles.ts:31`). STIV anade `stiv_velocity_profile`, `stiv_sigma_profile`, `stiv_angle_profile`, `stiv_sign_profile`,
`stiv_skip_profile`, `stiv_angle_manual_profile` (`PY/stiv_pipeline.py:24-33,675-691`); iWave anade `iwave_velocity_profile`,
`iwave_quality_profile`, `iwave_depth_profile`, `iwave_valid_profile`, `iwave_skip_profile` y el objeto `iwave_params`
(`PY/iwave_pipeline.py:52-60,830-834`). Velocidades en m/s, `Q` en m3/s, areas m2, anchos m.

`summary` (`PY/compute_section.py:1282-1334`): `{mean:{...}, std:{...}, cov:{...}}`, cada uno con `total_W,total_A,total_Q,mean_V,alpha,mean_Vs,max_depth,average_depth,measured_Q`.

Importante: los resultados dependen de `piv_results.json`; ver 2.5. Para que la GUI abra en "Processing" basta con las 22 claves basicas;
para abrir en "Results" hace falta `piv_results.json` + `settings.piv_results` + `distance` presente (`S/helpers/loadProjectHelpers.ts:222-224,343-347`).

`transformData` convierte con `toFixed(2)` a texto algunas claves (`measured_Q`, `total_A`...) solo en memoria (`HU/transformCrossSectionsData.ts:18-29`); en
disco son numeros.

Ejemplo minimo (Paso 3, valores derivados de `examples/results/pisco/section.json` + las claves `dir_*` que ese ejemplo de notebook no tiene):

```json
{
  "CS_default_1": {
    "bath": "/ruta/proyecto/pisco_section.csv",
    "rw_length": 22.154173421728018,
    "level": 1.765,
    "left_station": 2.0,
    "dir_xl": 49.94, "dir_yl": 935.04, "dir_xr": 1800.19, "dir_yr": 687.60,
    "dir_east_l": -1.48, "dir_north_l": 0.14, "dir_east_r": 20.67, "dir_north_r": 0.57,
    "xl": 49.94, "yl": 935.04, "xr": 1800.19, "yr": 687.60,
    "east_l": -1.48, "north_l": 0.14, "east_r": 20.67, "north_r": 0.57,
    "num_stations": 15,
    "alpha": 0.85
  }
}
```
(Los numeros de `xl..north_r` e `level/rw_length/left_station/num_stations/alpha` salen de `section.json`; `alpha` del ejemplo es 1, aqui 0.85 es el valor por defecto del CLI.
Los `dir_*` son una simplificacion: iguales a `xl..` cuando `left_station = 0`; con `left_station = 2.0` real serian distintos. No tomar los `dir_*` como datos reales.)

### 2.5 `piv_results.json`

Escribe Python con `json.dumps` sin indentacion (`PY/orchestrator.py:119`). Claves (`PY/piv_pipeline.py:231-240`, confirmadas con
`examples/results/pisco/piv_results.json`: `shape=[11,58]`, `x/y` de 638, `u/v/gradient` de 29 listas de 638, `u_median/v_median` de 638):

| Clave | Tipo | Detalle |
|---|---|---|
| `shape` | `[filas, columnas]` | rejilla; `x`,`y`,... son `shape` aplanado en orden row-major (x varia mas rapido) |
| `x`, `y` | lista de `filas*columnas` floats | centro de cada ventana de interrogacion, en PIXELES del frame, y hacia abajo |
| `u`, `v` | lista de `n_pares` listas de `filas*columnas` | desplazamiento en PIXELES entre `frame[i]` y `frame[i+1]` (pares consecutivos de `frames/` ordenados por nombre; `n_pares = n_frames-1`); `NaN` donde no hay dato |
| `u_median`, `v_median` | lista de `filas*columnas` | `nanmedian` sobre pares (`PY/piv_pipeline.py:228-229`) |
| `gradient` | igual a `u` | usado por las estadisticas y el perfil "seeded" |
| `_piv_params_hash` | string md5 | opcional; si falta o difiere, `analyze-all` recalcula (`PY/orchestrator.py:31,102-117`) |

`typevector` NO se guarda en este archivo (solo aparece en `piv-test`); la GUI lo recibe `undefined` (`S/hooks/useProjectSlice.ts:594`, `HU/readResultsPiv.ts:6`).
La GUI solo exige `x,y,u,v,u_median,v_median`; Python (`update-xsection`) exige ademas `shape` y `gradient` (`PY/compute_section.py:55-66,1111-1146`).
`step` y `fps` NO estan en este archivo: salen de `settings.video_range.step` y `settings.video.fps` (`H/getQuiver.ts:162-163`).
Tiempo entre frames consecutivos = `step / fps` s (`PY/compute_section.py:1397`). Es el unico vinculo entre pixeles/par y m/s.

Minimo valido (rejilla 1x2, 2 pares; derivado de la estructura del ejemplo `pisco`):

```json
{"shape":[1,2],"x":[48.0,80.0],"y":[684.8,684.8],
 "u_median":[NaN,1.2],"v_median":[NaN,-0.3],
 "u":[[NaN,1.1],[NaN,1.3]],"v":[[NaN,-0.2],[NaN,-0.4]],
 "gradient":[[NaN,0.0],[NaN,0.0]]}
```

### 2.6 Batimetria (`bath`, CSV dentro del proyecto)

Python (`PY/compute_section.py:128-166`): formatos csv/ods/xls/xlsx; col 0 = estacion (m, distancia a lo largo de la linea, creciente de izquierda a derecha), col 1 = cota del fondo (m, "stage"/elevacion en la MISMA cota que `level`); la primera fila SIEMPRE se descarta como cabecera
(`data.get_col(0)[1:]`, `:163-164`). Ejemplo real: `examples/data/bathymetry/pisco_section.csv` (`d,h` + 8 filas). La GUI escribe `station,stage` y filas `x,y` (`H/getBathimetry.ts:171-174`).
Detalles de normalizacion al importar en 4.1. El archivo guardado siempre esta en SI.

### 2.7 Mascara y `bbox.json`

- `bbox.json`: lista de 4 numeros `[x, y, ancho, alto]` en pixeles, minificado (`CLI/define_roi_masks.py:121-124`; `PY/define_roi_masks.py:194-240`),
  recortado a las dimensiones de la imagen.
- `mask.npy` (ROI), `usr_mask_<N>.npy`, `final_mask.npy`: matriz `uint8` de forma `(alto, ancho)` del frame, valores 0/1. Semantica: `1` = zona analizada,
  `0` = excluida (`PY/define_roi_masks.py:98-106,338-364`; los poligonos de usuario valen 0 dentro). `final_mask = AND` de ROI y mascaras de usuario (`:416`).
  Formato `.npy` NumPy v1.0 (little-endian `|u1`, `fortran_order False`, shape `(H, W)`; no esta definido en el repo: es el formato estandar de NumPy). Alternativa
  soportada por compatibilidad: `mask.json` / `final_mask.json` = lista de H listas de W enteros 0/1 (`H/loadProject.ts:89-93`, `CLI/utils.py:25-30`).
  Orden de busqueda de la GUI: `final_mask.npy`, `final_mask.json`, `mask.npy`, `mask.json` (`H/loadProject.ts:90-95`).
- `mask.png` / `final_mask.png`: RGBA, mismo tamano; pixeles con mascara 0 = negro con alfa 111, resto transparente (`CLI/define_roi_masks.py:17-34`). La GUI muestra
  este PNG: `getMask(...,'png')` busca `final_mask.png` y luego `mask.png` (`H/loadProject.ts:99-103`). Si no existe, el renderer arma la URL con el string "undefined"
  (`S/hooks/useProjectSlice.ts:570-573`, `:572`): no es fatal pero conviene escribir siempre el PNG.
- La mascara ROI se construye con las secciones: para cada seccion, caja rectangular de `height_roi` m centrada en la linea, rotada (`PY/define_roi_masks.py:40-65`); `height_roi` lo recomienda
  `recommend-height-roi` (`H/recommendRoiHeight.ts:9`, `CLI/define_roi_masks.py:44-67`) y se guarda en `settings.processing.roi_height`.
- `analyze-all` necesita `--mask` y `--bbox` (`H/getQuiver.ts:170-173`); si `maskPath`/`bboxPath` estan vacios el comando se arma mal. Que la GUI los regenere al entrar a "Processing": no verificado. La app iOS debe escribir
  `mask.npy`+`mask.png`+`bbox.json` junto con `xsections.json`.

### 2.8 Archivos de ipcam (3D)

- `grp_3d.json` (`H/calculate3dRectification.ts:21-65,95-96`): objeto con listas paralelas `X,Y,Z` (mundo, m), `x,y` (pixel; `0,0` = punto sin marcar), `label` (lista de pares `[etiqueta, indiceOriginal]`),
  `image` (nombre de imagen o null), `not_selected_X/Y/Z/x/y/label/image` (puntos descartados), `solution` (`"direct-solve"` | `"optimize-solution"`). Lector: `HU/parseGrp3dPoints.ts:1-59`
  (reconstruye por `indiceOriginal`; `wasEstablished = x!=0 && y!=0`).
- `full_grp_3d.json` (solo si algun punto quedo sin usar): `{X,Y,Z,x,y}` con todos los puntos marcados (`:67-92,97-99`). Para el calculo, un "grp dict" necesita `X,Y,Z,x,y` (`PY/coordinate_transform.py:467-469`). Ejemplo real: `examples/data/grps/full_grp_3d.json` (45 puntos; solo `X,Y,Z,x,y`).
- `camera_solution_3d.json` (`H/calculate3dRectification.ts:122-124`; contenido de `PY/coordinate_transform.py:534-559`): `camera_matrix` (3 listas de 4 = matriz P 3x4, row-major), `camera_position` `[X,Y,Z]`, `error` (media px de reproyeccion),
  `reprojection_errors` (lista), `projected_points` (lista de `[x,y]`), `uncertainty_ellipses`, `ortho_image_path` (string absoluto) y `ortho_extent`, y si `optimize-solution`: `num_points`, `point_indices`. El lector espera esas claves (`HU/parsedCameraSolution.ts:5-14`).
  Ejemplo real (parcial, de otra version de la libreria; sin ortho): `examples/results/juncal/transformation.json` = `{camera_matrix[3][4], camera_position[3], error}`.
- `ortho_image.png` en la carpeta del proyecto. `camera_solution.ortho_image_path` es absoluto y NO se reubica al mover el proyecto (`H/loadProject.ts` no lo toca): la ortoimagen 3D se vera rota; efecto cosmetico, no verificado mas alla de eso.

### 2.9 Salidas de STIV / iWave y caches

- `stis/<seccion>/sti_<id_estacion>.png`: PNG 8 bit en grises (matriz STI normalizada a 0-255; `PY/stiv_pipeline.py:640-646`), nombre `^sti_(\d+)\.png$` (`H/getStis.ts:6`). Carpeta borrada y recreada por cada corrida (`PY/stiv_pipeline.py:637-640`).
- `iwave_spectra/<seccion>/spectrum_<id>.png` + `spectra.json` (`{version, stations:[{station,kx_min,kx_max,kt_min,kt_max,curves,...}]}`, `H/getIwaveSpectra.ts:11-43,6`).
- `rectified/NNNNNN.jpg` (6 digitos, base 0): frames ortorectificados de iWave (`PY/iwave_pipeline.py:188-189`).
- `_stats_cache.json`: `{<nombreSeccion>: {geometry_hash, dense_distances[200], streamwise_vel_frames[n_pares][200], gradient_frames[n_pares][200]}}` (`PY/compute_section.py:26,1562-1567`). Si falta o el hash no coincide se recalcula. El hash cubre bancos, nivel, `left_station`, `step`, `fps` y matriz, pero NO el contenido de la batimetria (`:40-44`). Un proyecto iOS puede omitirlo; uno editado a mano con cache viejo puede dar estadisticas desactualizadas.
- `river.log`: concatenacion de bloques `{options:[...], output, error}` con `JSON.stringify(..., null, 4)+"\n"` (no es un unico JSON valido) (`HU/executeRiverCli.ts:183-192`). No se lee. No hace falta crearlo.

Lo que NO hay en este repo: ejemplos de `xsections.json` completos producidos por la GUI ni de `settings.json`. Las claves de 2.1 y 2.4 se infirieron de los escritores (`H/*.ts`, `PY/*.py`) y los lectores, no de un ejemplo.

---------------------------------------------------------------------------------------------------

## 3. Tipos de footage y como cambian los archivos

`settings.footage` toma 3 valores: `uav`, `oblique`, `ipcam` (`H/interfaces.ts:111`; `S/store/project/types.ts:71`). No hay un valor "3d": la rectificacion 3D es `ipcam`
(`S/constants/constants.ts:6-8`; `H/calculate3dRectification.ts`). `fixed_station` aparece solo en el nombre de un notebook (`examples/02c_fixed_station_transformation.ipynb`); en el codigo de GUI no existe como valor.

| | uav | oblique | ipcam |
|---|---|---|---|
| Paso 2 handler | `set-pixel-size` (`H/setPixelSize.ts`) | `set-control-points` (`H/setControlPoints.ts`) | `calculate-3d-rectification` + `save-transformation-matrix` |
| Clave de `settings` | `pixel_size` + `transformation{matrix,resolution,extent}` | `control_points` + `transformation{matrix,resolution,extent,roi}` | `grp_3d`, `camera_solution_3d`, `rectification_3d_images`, `transformation{matrix}` |
| CLI usada | `get-uav-transformation-matrix` | `get-oblique-transformation-matrix` | `get-camera-solution` (la matriz de nivel la calcula la GUI) |
| Archivos extra | `transformed_image.png` | `transformed_image.png` | `ortho_image.png`, `grp_3d.json`, `full_grp_3d.json`, `camera_solution_3d.json` |
| Entrada del usuario | 2 puntos pixel + longitud o tamano de pixel | 4 puntos pixel + 6 distancias (+ anclas opcionales) | N puntos de control X,Y,Z con pixel (x,y); archivo de puntos (4.2); batimetria en cota absoluta (no profundidad) |
| Matriz | fija | fija | depende del nivel de agua; cambia al cambiar `level` y se re-guarda |

Notas:

- La matriz de ipcam depende del nivel (`H/saveTransformationMatrix.ts:6-9`); en ipcam la batimetria debe ser cota absoluta en el mismo marco Z que los puntos (profundidad se rechaza si hay `zLimits`:
  `H/getBathimetry.ts:133-138`).
- `frames/`, `xsections.json`, `piv_results.json`, mascara, STIV/iWave, `video_range` son identicos en los tres modos; solo cambian los archivos de transformacion y las claves de `settings`.
- Una sola matriz por proyecto (no por seccion): `transformation_matrix.json`. Todas las secciones de un proyecto comparten `level` en ipcam (`S/hooks/useSectionSlice.ts:476-520`).

---------------------------------------------------------------------------------------------------

## 4. Archivos que importa el usuario

Extensiones aceptadas por los selectores/arrastre: `csv, tsv, xlsx, xls, xlsm, ods, fods, prn, dif, sylk` (`HU/validateFile.ts:4`); la validacion al arrastrar solo mira `endsWith` de la extension (`:19-23`).
Se leen con SheetJS (`readFile` + `sheet_to_json(header:1)`, primera hoja) (`H/getBathimetry.ts:28-58`, `H/getDistances.ts:45-50`, `H/getPoints.ts:35-40`).

### 4.1 Batimetria (`import` -> `bath` CSV)

`H/getBathimetry.ts:17-199`.

- Columnas: col 0 = x (estacion, distancia horizontal), col 1 = y (cota). Una cabecera es opcional en la importacion (se descarta la fila 0 si no es numerica;
  cualquier otra fila no numerica = error `invalidBathimetryFileFormat`, `:97-102`). Cabecera de salida: `station,stage` (`:172`).
- Separadores aceptados en CSV: `,`; `;` y tabulador (marca normalizacion); heuristicas para comas decimales (`"0,1,765"` -> `0` y `1.765`; 4 campos -> dos decimales) (`:34-54`).
  `smartParseFloat` acepta `1.234,56` y `19,97` (`HU/parseDistancesData.ts:9-38`).
- Unidades: metros, o pies si el proyecto esta en `imperial` (se multiplica x0.3048 en x e y) (`:117-121`). La unidad de la fuente NO queda registrada: el archivo guardado es siempre SI. Al abrir un proyecto, la GUI vuelve a invocar
  `get-bathimetry` solo con `{path}` sin `unitSistem` (`S/helpers/loadProjectHelpers.ts:237`), por lo que cualquier CSV del proyecto se interpreta como metros.
- Orientacion: si `x` decrece, se invierte el orden (`:296-303`). Si el perfil parece PROFUNDIDAD (forma de colina respecto a la cuerda; area con signo >= 0: `analyzeLine`, `:248-289`), se convierte a cota `y = maxY - y` (con anclas de profundidad 0 en ambos extremos, `:145-152,296,310`). Para que el archivo no se reescriba: entregar cota (valle: orillas altas, centro bajo), x creciente, con cabecera, separador coma.
- Se escribe `<nombre>_modified.csv` si hubo transformacion, separador no estandar, imperial, no era CSV, o no tenia cabecera (`:165-188`); de lo contrario se copia tal cual al proyecto (`HU/copyIntoProject.ts`). Un mismo nombre con distinto contenido nunca se pisa (`_2`, `_3`, ...) (`HU/copyIntoProject.ts:5-51`).
- Importante: Python descarta SIEMPRE la primera fila (`PY/compute_section.py:163-164`). Un CSV sin cabecera pierde su primer punto; la GUI lo repara agregando cabecera (`:108-115`).

### 4.2 Puntos de control (ipcam)

`H/getPoints.ts:61-124`. Columnas: `Label, X, Y, Z [, x, y]` (minimo 4 columnas en la primera fila, si no `invalidPointsFileFormat`, `:62-64`).
La fila de cabecera se detecta solo si `col0.toUpperCase()==='LABEL'` y las columnas 1-3 son strings (`:74-82`); cualquier otra cabecera da NaN -> error.
X, Y, Z (m; pies si imperial): se redondean a 2 decimales y luego, si imperial, x0.3048 a 4 decimales (`:84-90`). Si hay columnas 5-6: pixel `x,y` (1 decimal) y `wasEstablished = true` (`:99-103`); si no, `x=y=0`.

### 4.3 Distancias (oblique)

`H/getDistances.ts`, `HU/parseDistancesData.ts:82-155`. Seis valores `d12,d23,d34,d41,d13,d24` (m; pies si imperial, x0.3048), >= 0, finitos.
Formatos: 1 columna con 6 valores en el orden canonico; o 2 columnas `[etiqueta, valor]` en cualquier orden. Etiquetas: `d12`, `D12`, `12`, `1-2`, `1_2`, `1 2`, `1,2`, `1;2`, o serial de fecha de Excel (`:55-69`); `41` y `14` -> `d41`.
Cabecera opcional solo si el archivo tiene 7 filas no vacias y la primera no tiene numeros (`:92-99`); lineas vacias ignoradas.

---------------------------------------------------------------------------------------------------

## 5. Frames: nombre, formato, derivacion de tiempos

### 5.1 Nombre y formato

- JPEG, calidad 95 (`PY/video_to_frames.py:98`; `PY/frames_to_frames.py:116`), BGR/RGB 3 canales.
- Nombre: `{indice:010d}.jpg` (10 digitos con ceros), donde `indice` es el indice del frame en el VIDEO FUENTE (no un contador 0..N): solo se extraen los multiplos de `step`
  dentro de `[start, end)` (`PY/video_to_frames.py:140-157`: `frame % every == 0`, `frame < end`, `save_path = frames_dir / f"{frame:010d}.jpg"`).
  Ejemplo real: `examples/data/frames/canuelas/` = 45 frames `0000000000.jpg, 0000000002.jpg, ..., 0000000088.jpg` (paso 2, inicio 0; 1920x1080).
  Se usa `frame % every` sobre el indice absoluto (no `(frame-start) % every`), por lo que con `start` no multiplo de `step` el primer frame es el primer multiplo >= start.
- Para carpeta de frames importada: mismo patron `{indice:010d}.jpg` con `indice` = posicion (0-based) del archivo en la carpeta origen ordenada de forma natural (`PY/frames_to_frames.py:24-43,122`).
  La seleccion usa `i % every == 0` tambien (`:40-43`). Se convierte PNG/JPEG a `.jpg`.
- Extension `.jpg` en minusculas: PIV hace `glob("*.jpg")` + `sorted` por nombre (`PY/piv_pipeline.py:136`, `PY/orchestrator.py:103`); STIV acepta `.jpg/.jpeg` con orden natural (`PY/stiv_pipeline.py:91-95`).
  El orden lexicografico solo equivale al temporal gracias al relleno de ceros: no usar nombres sin relleno.
- Resolucion: todos los frames iguales; `factor` reduce con `cv2.resize(INTER_AREA)` (`PY/video_to_frames.py:76,138`); lente (`undistort`) recorta con `roi` si se pide (`:48-58`). No se aplican rotaciones EXIF aqui; la rotacion se maneja solo en la metadata (`HU/getVideoMetadata.ts:50-58`).
- `loadFrames` lista TODOS los archivos de `frames/` salvo `background.jpg` (`H/loadProject.ts:30-41`): no dejar archivos extra (`.DS_Store`, miniaturas, etc.) en `frames/`. `get-images` indexa por posicion en `readdir` (`H/getImages.ts:14-16`).
- La miniatura de "recientes" usa el primer `.jpg/.jpeg/.png` en orden alfabetico, excluyendo `background.jpg` (`H/projectThumbnails.ts:13-16`).
- Frames estabilizados: se generan en `frames_stabilized`, y con `--replace` (siempre en la GUI) sobreescriben `frames/` (`CLI/video_to_frames.py:38-59`; `HU/extractFrames.ts:98`).

### 5.2 Como se parametriza la extraccion (lo que debe quedar en `settings.json`)

`HU/extractFrames.ts:68-81,123-139`, `S/hooks/useProjectSlice.ts:296-320,363-372`:

- Video: la GUI trabaja en segundos; `start_frame = floor(startTime * fps)`, `end_frame = floor(endTime * fps)` (`S/hooks/useProjectSlice.ts:301-302`), `step` entero, `factor`.
  Se guardan tal cual en `video_range{start,end,step,factor}` (indices fuente, no tiempos). Al abrir, `startTime = round(start/fps, 2)` (`S/helpers/loadProjectHelpers.ts:33-38`).
- Frames (carpeta): el formulario usa numeros de frame 1-based con `end` incluido: `startFrame = start-1`, `endFrame = end` (`C/framesSource.ts:55-64`).
- `fps` es entero redondeado para videos (`HU/getVideoMetadata.ts:67`): un video de 29.97 fps queda con `fps: 30` y la velocidad se calcula con 30. Para carpeta de frames `fps = 1000/intervalMs` sin redondeo (`C/framesSource.ts:29-31`).

### 5.3 Derivacion del tiempo entre frames

- `dt = step / fps` segundos entre dos frames consecutivos de `frames/` (`PY/compute_section.py:1397`; `PY/stiv_pipeline.py:650` `seconds_per_pix = step/float(fps)`).
  `step` = `settings.video_range.step`; `fps` = `settings.video.fps` (`H/getQuiver.ts:162-163`).
  Los NOMBRES de frame no se usan para el calculo de dt: solo para el orden. Si se ponen frames con otro paso real, hay que ajustar `video_range.step` para que `dt` sea correcto.
- `video.time_between_frames` (= `1/fps` redondeado a 2 decimales) no entra en el calculo para videos; solo `intervalFromSettings` lo lee, y solo para proyectos `source: "frames"` (`C/framesSource.ts:98-101`).
- `video.total_length` -> duracion mostrada (`HU/videoFallback.ts:25`).

---------------------------------------------------------------------------------------------------

## 6. Codificacion de texto y rutas absolutas

### 6.1 Codificaciones (exactas)

| Archivo | Lectura / escritura | Cita |
|---|---|---|
| `settings.json` | UTF-8 siempre (lectura y escritura) en todos los handlers; ningun `latin1` | `HU/prepareProject.ts:18,37`; `H/setControlPoints.ts:11,103`; `H/setSections.ts:23,31`; `H/setProjectMetadata.ts:12` (lectura `'utf8'`, escritura sin encoding = UTF-8 por defecto de Node, `:29`) |
| `xsections.json` | **UTF-8** en `load-project`, `prepareProject`, `setSections`, `clearCrossSections`, `locateBathymetry`; **`latin1` en Windows (win32) y UTF-8 en el resto**, lectura Y escritura, en `get-results-single`, `get-results-all`, `get-results-load`, `set-stiv-manual-angles` | UTF-8: `H/loadProject.ts:21`, `HU/prepareProject.ts:25,29`, `H/setSections.ts:32`, `HU/clearCrossSections.ts:5,53`, `H/locateBathymetry.ts:31,37`. Latin1/win32: `H/getResultData.ts:9-13,36,41,80,97,107,149`; `H/loadResults.ts:8-12,20`; `H/setStivManualAngles.ts:9,43,53` |
| `piv_results.json` | UTF-8 (lectura Node); Python escribe con `write_text` sin encoding (codificacion regional) pero el contenido es ASCII | `HU/readResultsPiv.ts:5`; `PY/orchestrator.py:108,114,119` |
| Batimetria CSV | Node lee y escribe UTF-8 (`placeIntoProject` -> `Buffer.from(content,'utf-8')`); Python la abre con `open(mode="r")` sin encoding (codificacion regional) via tablib | `H/getBathimetry.ts:35`; `HU/copyIntoProject.ts:31`; `PY/compute_section.py:153-160` |
| `transformation_matrix.json`, `bbox.json`, `grp_3d.json`, `camera_solution_3d.json`, `river.log` | UTF-8 | `HU/createMatrix.ts:40`; `CLI/define_roi_masks.py:121`; `H/calculate3dRectification.ts:96,124`; `HU/executeRiverCli.ts:192` |
| `grp_3d.json` | UTF-8 (writeFile sin encoding: UTF-8 por defecto) | `H/calculate3dRectification.ts:96-98` |
| Python: lectura de xsections/matriz/piv | `click.File()` (`CLI/compute_section.py:12-14`) y `Path.read_text()` (`PY/orchestrator.py:108,122,123`) sin `encoding=`: usan la codificacion regional del proceso (en Windows, tipicamente cp1252) | |
| Python: escritura de salida | `json.dumps` con `ensure_ascii` por defecto: ASCII puro (caracteres no ASCII salen como `\uXXXX`) | `PY/orchestrator.py:119,134,212`; `CLI/utils.py:48` |

Consecuencias (documentadas por el propio codigo): Python en Windows convierte la ruta de batimetria con `bath_file_path.encode("latin-1").decode()` para compensar la lectura regional del JSON UTF-8
(`PY/compute_section.py:142-143`); una ruta con caracteres fuera de latin-1 (cirilico, CJK) lanza `UnicodeEncodeError` -> "Unexpected error" (`CLI/utils.py:44-46`). Que un nombre de seccion con acentos
sobreviva intacto un ciclo Python->Node->Python en Windows: no verificado (la nota `H/setStivManualAngles.ts:7-8` dice que el `latin1` evita "corromper nombres de seccion"). Recomendacion: nombres de seccion y de archivo de batimetria solo ASCII.

Un lector Swift debe leer SIEMPRE `xsections.json` como bytes y decodificar como UTF-8 (para archivos escritos por la iOS app o por el escritorio no-Windows). Si el archivo fue escrito por el escritorio en Windows con `latin1`, los bytes
siguen siendo los mismos que UTF-8 original (latin1 es transparente byte a byte en el ciclo lectura-escritura de Node), asi que tambien se decodifican bien como UTF-8 (salvo que Python haya reescrito el archivo con `\uXXXX`).

### 6.2 Rutas absolutas y movimiento del proyecto (feature "Locate")

Claves de `settings.json` con rutas absolutas: `video.filepath`, `xsections`, `piv_results`, `transformation.matrix`, `stabilization_regions_path`, `camera_solution_3d`, `grp_3d`, `rectification_3d_images`, `lens_correction`.
En `xsections.json`: `bath` por seccion. En `camera_solution_3d.json`: `ortho_image_path`.

Al abrir (`prepareProject`, `HU/prepareProject.ts:14-56`):

1. "Claves propias" -> reubicadas a `<carpeta abierta>/<basename(valor)>` sin consultar la ruta guardada: `xsections`, `piv_results`, `transformation.matrix`, `stabilization_regions_path`, `camera_solution_3d`, `grp_3d`
   (`HU/relocateProject.ts:5-12,30-42`). Solo si el valor es string no vacio; si "ya es el mismo archivo" (mismo nombre y misma carpeta en disco, `HU/samePath.ts:23-25`) se deja. Se reescribe `settings.json` (indent 4) y se anade una entrada a `river.log`
   (`HU/prepareProject.ts:37-50`). Un valor relativo o de otro SO (`C:\...`) tambien se corrige. Consecuencia para iOS: se puede escribir solo el nombre (`"xsections.json"`) o una ruta de la app; el escritorio la normaliza (no probado en la practica; deducido del codigo).
2. Batimetria (`bath`): si el archivo guardado existe, se copia al proyecto (sin pisar uno distinto del mismo nombre); si no, se usa el archivo del mismo nombre ya presente en la carpeta; si ninguno existe -> `missingInputs: [{kind:'bathymetry', section, path}]` y se conserva la ruta guardada. Se reescribe solo el token `"bath"` en el texto de `xsections.json`
   (`HU/resolveBathymetry.ts:10-71`, `HU/copyIntoProject.ts:58-69`). Si el proyecto se movio (`movedFrom`), el archivo propio del proyecto gana aunque la carpeta original siga existiendo (`:25-29`).
3. Video: `videoMissing` si `video.filepath` no existe (`HU/prepareProject.ts:52-53`); la carga NO falla; se usan los metadatos de `settings.video` (`H/loadProject.ts:152-155`). "Locate" (`H/locateVideo.ts`): acepta solo un video con igual `width`,`height` y `round(fps)` (y mismo numero de frames en proyecto de frames) y sobreescribe `video.filepath` (`:49-72`; comparacion `HU/videoFallback.ts:46-53`).
   Para proyectos de frames el Locate pide la carpeta origen y valida resolucion y `frame_count` (`:19-47`).
4. `rectification_3d_images`: si no existe -> `missingInputs: {kind:'ipcamImages', path}` (`HU/prepareProject.ts:32-35`). `lens_correction` no se valida al abrir.
5. `river.log` registra "project moved from X to Y" y "copied into the project".

Resumen: el escritorio no depende de rutas absolutas para abrir (salvo `video.filepath` como string); iOS puede escribir rutas simbolicas.

---------------------------------------------------------------------------------------------------

## 7. Checklist de compatibilidad para la app iOS

Para que el escritorio ABRA un proyecto iOS:

1. Crear carpeta `<...>/<nombre>/<YYYYMMDDTHHMM>/` (convencion) con `settings.json` UTF-8 sin BOM, JSON estricto (sin `NaN` en settings).
2. `settings.json` minimo: `creation_date`, `footage` (`uav|oblique|ipcam`), `language`, `unit_system` (`si|imperial`), `video{filepath(string), name, total_length, time_between_frames, fps(entero), resolution{width,height}}` (2.1).
   Poner `filepath` como string aunque el video no este en el escritorio (resultara `videoMissing`, abre igualmente). Si el origen son fotogramas de iOS, usar `video.source:"frames"` + `frame_count` + `filepath` = carpeta.
3. Crear `frames/` con `{indice:010d}.jpg`, JPEG, mismo tamano, nada mas dentro; indice = indice del video fuente (multiplos de `step`) o posicion 0-based.
4. Escribir `video_range{start,end,step,factor}` (obligatorio apenas haya `pixel_size`/`control_points`/`xsections`/`piv_results`).
   Elegir `step` y `fps` coherentes con el tiempo real entre frames consecutivos: `dt = step/fps` (5.3).
5. Geometria: escribir `transformation_matrix.json` (3x3 row-major, pixel -> mundo real, SI) y `settings.transformation.matrix`, junto con `pixel_size` (UAV) o `control_points` (oblique) o los archivos 3D (ipcam).
   Escribir tambien `transformed_image.png` (o `ortho_image.png`) si se quiere vista previa; `resolution` y `extent` son metros/pixel y `[Emin,Emax,Nmin,Nmax]` de esa imagen.
6. `xsections.json` con las 22 claves basicas por seccion (2.4) + `settings.xsections` (ruta cualquiera: se reubica; el archivo DEBE existir si la clave esta). Copiar la batimetria CSV a la carpeta del proyecto con cabecera `station,stage`,
   cota (no profundidad), x creciente, coma, ASCII, SI.
7. `mask.npy`+`mask.png`+`bbox.json` (y `final_mask.*` si hay mascaras de usuario) si el proyecto tiene `xsections`. Alinear tamano con los frames.
8. Si ya hay resultados: `piv_results.json` (con `shape`, `gradient`, `NaN` bare) + `settings.piv_results`, y `xsections.json` enriquecido con arrays por estacion (2.4). Si no, no escribir `piv_results` y la GUI abrira en "Processing".
9. Todo en SI. `unit_system` solo cambia la visualizacion.

Para que iOS ABRA un proyecto del escritorio:

- Parsear `settings.json` (UTF-8). Tolerar claves ausentes (casi todo es opcional segun el paso alcanzado) y claves desconocidas (conservarlas al reescribir; el escritorio agrega claves entre versiones).
- Parsear `xsections.json`, `piv_results.json` con preprocesado de `NaN`/`Infinity`/`-Infinity` (`HU/sanitizeJson.ts:8-12`). `transformation_matrix.json` es JSON estricto.
- Las rutas absolutas de `settings.json` no sirven en iOS: derivar siempre `<carpeta proyecto>/<basename>` (igual que `HU/relocateProject.ts:36`) y buscar `bath` por basename dentro de la carpeta.
- Lista de frames: ordenar `frames/*.jpg` por nombre (orden lexicografico = temporal), ignorar `background.jpg`.
- `xsections` puede traer `summary` y `null` (de NaN saneado) en arrays por estacion.
- Para matrices ipcam, ojo: `transformation_matrix.json` es especifica del `level` actual de agua; si se cambia el nivel hay que recalcularla desde `camera_matrix` (2.3).

Trampas conocidas:

- `video.filepath` ausente => el escritorio falla la carga (`H/loadProject.ts:68-69`); `frames/` ausente => falla (`:30`).
- `settings.xsections`/`grp_3d`/`camera_solution_3d` apuntando a archivo inexistente => falla la carga (2.1 punto 4-5).
- `video_range` ausente cuando hay `xsections`/`pixel_size`/`control_points`/`piv_results` => `notValidDirectory`.
- Escribir una matriz nueva invalida (borra) xsections, bbox, mascara y piv_results (`HU/createMatrix.ts:8-34`): imitarlo o eliminar tambien `settings.xsections` y `settings.piv_results`.
- Cambiar `bath`/geometria sin borrar `_stats_cache.json`: el cache no hashea el contenido de la batimetria (`PY/compute_section.py:40-44`).
- Frames fuera de la convencion (`.JPG`, sin ceros, archivos extra en `frames/`) o con distinto tamano a la mascara: la mascara no se aplica a STIV/iWave si el tamano difiere (`PY/orchestrator.py:62-75`) y PIV leerá mal.
- `fps` entero: 29.97 -> 30. Si iOS escribe 29.97 el escritorio lo toma tal cual (`H/getQuiver.ts:163`), pero `Locate` compara `round(fps)` (`HU/videoFallback.ts:46-53`).
- Rutas/nombres de seccion no ASCII en Windows: ver 6.1.
- BOM al inicio de JSON: rompe `JSON.parse`.
- NaN en piv_results: usar token `NaN`; `null` no verificado para Python.
- Unidades: no guardar pies; el escritorio no tiene campo que indique la unidad de la batimetria guardada (siempre se asume m, 4.1).
- Ambiguedades abiertas (no verificado en codigo): (a) si la GUI regenera mascara/bbox al entrar a Processing sin ellos; (b) tipo exacto (string o number) de `video.total_length` generado por ffprobe; (c) unidad de `colorbar_limits`; (d) reescalado al cambiar `factor` con matriz ya calculada;
  (e) comportamiento de `getImageSize` cuando falta `transformed_image.png` y hay `pixel_size`/`control_points`; (f) criterio exacto de signo de velocidad "streamwise".
