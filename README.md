# BITCAM

Câmera com fotografia computacional. O app Android nativo fica em `android/`; a raiz guarda o protótipo web (PWA) do pipeline de burst.

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

## App Android (moto g54 5G)

`android/` é um app nativo (Kotlin + C++) feito para o moto g54 5G, que expõe Camera2 `LEVEL_3` com RAW. Ele não usa o motor web da raiz.

### Etapa 1 — RAW e cor (atual)

- Captura um quadro RAW (4080×3060) antes do processamento da Motorola.
- Revelação própria em C++ (`app/src/main/cpp/bitraw.cpp`): nível de preto, sombreamento da lente, balanço de branco, demosaico Malvar-He-Cutler, matrizes de cor do sensor, curva de tons, redução de ruído de cor e nitidez leve.
- Balanço de branco: **Auto BIT** (estimado pela cena), **Motorola** (o do aparelho), **Kelvin** (temperatura, tint e predefinições) e **Travar último** (fixa o valor da última foto).
- Salva JPEG e, opcionalmente, o DNG em `DCIM/BITCAM`.
- Toque para focar; botões de volume disparam.

Ainda não tem: HDR local e modo noite (Etapa 3), zoom.

### Etapa 2 — fusão de burst RAW (atual)

- Cada foto são 4, 6 ou 8 quadros RAW (mais quadros quanto maior o ISO), com exposição e cor travadas.
- A referência é o quadro mais nítido dos três primeiros.
- Alinhamento em pirâmide: global e depois por blocos, para absorver rotação leve da mão e paralaxe.
- Fusão por pixel com rejeição de movimento: cada quadro só entra onde a diferença para a referência, medida nas quatro amostras Bayer, é compatível com o ruído. O ruído é medido nos próprios quadros.
- O resultado segue para a mesma revelação da Etapa 1, que passa a aplicar menos redução de ruído.
- O JPEG leva no EXIF (ImageDescription) o diagnóstico da revelação: quadros fundidos, ruído medido, balanço de branco, níveis de preto e branco.
- O DNG vem desligado por padrão: é o negativo cru do primeiro quadro, não a foto final.

### Vídeo V1 — cor, brilho e compressão (atual)

- Modo **Vídeo** em 1080p a 30 qps constantes, HEVC a ~36 Mbps (H.264 a ~48 Mbps se o aparelho recusar HEVC), áudio AAC.
- **Travar cor e brilho**: ao gravar, exposição e balanço de branco ficam fixos. **Reajustar** solta as travas por 1,5 s para se adaptar a um novo ambiente.
- Balanço de branco **Motorola** ou **Kelvin** (o Auto BIT depende do RAW e não vale para vídeo; "Travar último" traz o valor da última foto).
- **Natural**: desliga a nitidez artificial do aparelho e usa uma curva de contraste própria.
- **Estabilizar**: estabilização eletrônica somada à óptica.

O vídeo passa pelo processador de imagem da Motorola; não há fusão de quadros. Pouca luz fica para a etapa V2.

### Compilar e testar

```sh
cd android
ANDROID_HOME=/caminho/do/sdk ./gradlew assembleRelease
# APK em app/build/outputs/apk/release/app-release.apk

# teste da revelação no computador, com um sensor simulado
cd app/src
g++ -O2 -std=c++17 -fsanitize=address,undefined -Imain/cpp test/cpp/test_bitraw.cpp main/cpp/bitraw.cpp -lpthread -o /tmp/t && /tmp/t
```

Precisa do SDK 35, NDK 27.2.12479018 e CMake 3.22.1.

O APK é assinado com `android/bitcam.keystore`, uma chave fixa e pública neste repositório, para que builds novos atualizem o app já instalado. Ela serve para instalação direta; para publicar na Play Store, troque por uma chave privada.
