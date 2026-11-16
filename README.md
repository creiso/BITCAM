# BITCAM

Câmera com fotografia computacional para Android (APK) e para o navegador (PWA). As duas versões usam o mesmo pipeline.

## O que ela faz a cada foto

1. **Burst** — captura 8 quadros (16 no modo Noite) na resolução máxima que o navegador entrega.
2. **Referência nítida** — escolhe o quadro mais nítido dos três primeiros.
3. **Alinhamento** — compensa o tremor da mão com busca em pirâmide (até ±96 px).
4. **Fusão com rejeição de movimento** — média ponderada por pixel; o que se mexeu fica só com a referência, sem fantasma. O ruído cai perto de √N.
5. **HDR local** — camada-base com filtro guiado: levanta sombras e segura altas-luzes sem achatar o contraste.
6. **Acabamento** — nitidez com coring, vibrance, leve calor e curva em S.

O botão **BIT** desliga tudo isso e salva o quadro direto do sensor. No visualizador, **Segure: original** compara a foto final com um quadro único.

## Controles

Aparecem só quando o aparelho/navegador os expõe: zoom (botões e pinça), lanterna como flash, compensação de exposição, toque para focar, troca frontal/traseira e lentes traseiras extras (L1, L2…).

## Limites

- O navegador entrega quadros já processados pelo aparelho (sem RAW), em geral até 4K (~8 MP), não os 12–48 MP do sensor.
- O alinhamento é só translação global: rotação forte da mão reduz o ganho da fusão nas bordas.
- Sem modo retrato, sem vídeo, sem EXIF.
- No iPhone, o Safari não expõe zoom, lanterna nem exposição; o burst e o processamento funcionam.

## Rodar

A câmera exige HTTPS (ou `localhost`).

```sh
python3 -m http.server 8000   # abra http://localhost:8000
node test/pipeline.test.js    # testes do pipeline
```

Para usar no celular, publique a pasta em qualquer hospedagem estática com HTTPS. No GitHub: **Settings → Pages → Deploy from a branch**, raiz do branch. Não há etapa de build.

## App Android

`android/` é um projeto Gradle em Java: uma casca nativa (WebView) que empacota os arquivos da raiz como assets e acrescenta:

- permissão de câmera do sistema;
- fotos gravadas direto na galeria, em `DCIM/BITCAM`;
- botões de volume como disparador;
- tela cheia, tela sempre ligada, botão voltar fecha o visualizador.

Requer Android 10 ou superior.

```sh
cd android
ANDROID_HOME=/caminho/do/sdk ./gradlew assembleRelease
# APK em app/build/outputs/apk/release/app-release.apk
```

O APK é assinado com `android/bitcam.keystore`, uma chave fixa e pública neste repositório, para que builds novos atualizem o app já instalado. Ela serve para instalação direta; para publicar na Play Store, troque por uma chave privada.
