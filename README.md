# OPPO Telefoto

Projeto Android experimental em Kotlin usando Camera2 API. Inclui seleção dos IDs de câmera que o Android expõe, prévia, zoom por recorte, compensação de exposição e foco manual quando suportado.

## Gerar APK
O workflow GitHub Actions executa `gradle assembleDebug` a cada push em `main` e publica o APK debug como artefato da execução.

## Limitações importantes
O aplicativo ainda é um protótipo: não tira/faz gravação de fotos, e a lista de IDs do Camera2 não prova por si só qual módulo físico é uma teleobjetiva. Fabricantes podem ocultar módulos ou expor zoom híbrido; a confirmação real depende dos metadados e testes no aparelho exato. O APK de debug não é uma versão final assinada para distribuição.
