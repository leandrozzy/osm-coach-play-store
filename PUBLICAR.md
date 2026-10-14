# Como publicar o Manager Coach IA na Play Store

Guia na ordem em que as coisas acontecem. O que já está pronto no código está marcado com ✅.

## O que já está feito no código
- ✅ Nome e pacote próprios: **Manager Coach IA** / `com.leandrozzy.managercoach` (o pacote **não pode mudar** depois da 1ª publicação).
- ✅ Captura pela **gravação de tela do Android** (MediaProjection) com aviso antes da 1ª captura, permissão do Android a cada captura e notificação fixa com **Encerrar**. Sem serviço de Acessibilidade.
- ✅ Sem permissões restritas (`USE_EXACT_ALARM`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` saíram).
- ✅ Boas-vindas: o que o app faz, aviso de app independente, apelido no jogo, chave de IA com passo a passo (opcional), notificações.
- ✅ Chaves de IA guardadas criptografadas (Android Keystore).
- ✅ Premium (compra única `premium_vitalicio`) pelo Google Play Billing: libera os 4 slots e tira anúncios; botão Restaurar compra.
- ✅ Banner do AdMob só no plano grátis, depois do consentimento (Google UMP / LGPD / GDPR).
- ✅ Firebase Crashlytics + Analytics preparados (ligam quando houver `google-services.json`; o usuário pode desligar em Ajustes).
- ✅ Ajustes → Privacidade: política, privacidade dos anúncios, apagar todos os dados.
- ✅ `targetSdk 36`, AAB assinado gerado pelo CI.
- ✅ Política de privacidade em `docs/privacy.html` e imagens da loja em `docs/loja/`.

## 1. Criar a chave de envio (upload key) — uma vez só
No seu computador (precisa do Java instalado):
```
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```
Guarde o arquivo `upload.jks` e a senha em lugar seguro (se perder, dá para pedir troca ao Google, mas demora).

No GitHub do repositório → **Settings → Secrets and variables → Actions → New repository secret**:
| Secret | Valor |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | conteúdo de `base64 -w0 upload.jks` (no Windows: `certutil -encode upload.jks out.txt`, sem as linhas BEGIN/END) |
| `ANDROID_SIGNING_PASSWORD` | a senha da chave |
| `ANDROID_KEY_ALIAS` | `upload` |

## 2. Publicar a política de privacidade
1. Abra `docs/privacy.html` e troque **[SEU E-MAIL DE CONTATO]** pelo e-mail de suporte.
2. GitHub → **Settings → Pages** → Source: *Deploy from a branch* → branch `main`, pasta `/docs`.
   (O GitHub Pages grátis exige repositório **público**. Se quiser o código privado, publique a política no Google Sites e troque `PRIVACY_URL` em `MainActivity.kt`.)
3. A URL fica `https://leandrozzy.github.io/osm-coach-play-store/privacy.html`.

## 3. AdMob (anúncios)
1. Crie a conta em admob.google.com → **Apps → Adicionar app** → Android, ainda não publicado.
2. Crie um bloco **Banner**.
3. Secrets no GitHub: `ADMOB_APP_ID` (ca-app-pub-…~…) e `ADMOB_BANNER_ID` (ca-app-pub-…/…).
   Sem eles o app usa os IDs de **teste** do Google (anúncio de exemplo).
4. Em **Privacidade e mensagens**, crie a mensagem de consentimento para **LGPD** e **GDPR** (o app já mostra o formulário).
5. Depois de publicado, vincule o app da Play Store no AdMob e publique o `app-ads.txt` (o AdMob mostra o conteúdo; pode ir em `docs/app-ads.txt` se usar um domínio próprio).

## 4. Firebase (monitoramento) — opcional
1. console.firebase.google.com → novo projeto → **Adicionar app Android** com o pacote `com.leandrozzy.managercoach`.
2. Baixe o `google-services.json` e crie o secret `GOOGLE_SERVICES_JSON` com o conteúdo inteiro do arquivo.
3. No Firebase, ative o **Crashlytics**. O Play Console também mostra travamentos em **Android vitals** sem nada disso.

## 5. Criar o app no Play Console
1. **Criar app** → nome *Manager Coach IA*, idioma Português (Brasil), **App**, **Gratuito** (o Premium é compra dentro do app).
2. **Configurar o app** (painel lista tudo):
   - **Política de privacidade**: a URL do passo 2.
   - **Acesso ao app**: “todas as funcionalidades sem restrição” (não há login).
   - **Anúncios**: “Sim, contém anúncios”.
   - **Classificação do conteúdo**: questionário (sem violência, sem jogos de azar; marque que tem compras e anúncios). Deve sair Livre/10+.
   - **Público-alvo**: 13+ (ou 18+). **Não** marque crianças.
   - **Segurança dos dados**: ver tabela abaixo.
   - **Apps de notícias / saúde / governo / financeiros**: não.
   - **Serviço em primeiro plano**: declarar `mediaProjection` → “Gravação de tela para ler o jogo enquanto o usuário joga; iniciada pelo usuário, com notificação”. Vídeo curto mostrando: tocar em capturar → permissão do Android → notificação → Encerrar.
3. **Página principal da loja**: textos de `docs/loja/textos.md`, ícone `docs/loja/icone-512.png`, recurso gráfico `docs/loja/destaque-1024x500.png` e de 2 a 8 prints do **app** (Hoje, Tática, Elenco, Resumo). Não use prints do jogo como imagem principal.
4. **Produtos no app → Produtos únicos**: criar `premium_vitalicio` (nome “Premium”, preço sugerido R$ 14,90–24,90) e **ativar**.

### Segurança dos dados (o que marcar)
| Dado | Coletado/compartilhado | Por quê | Obrigatório? |
|---|---|---|---|
| Identificador do dispositivo (ID de publicidade) | Coletado e compartilhado (AdMob) | Publicidade, análise | Não (consentimento) |
| Diagnósticos (travamentos, desempenho) | Coletado (Firebase) | Análise | Não (pode desligar) |
| Interações no app | Coletado (Firebase Analytics) | Análise | Não (pode desligar) |
| Fotos e vídeos / conteúdo da tela | **Compartilhado só quando o usuário usa a IA** com a própria chave (Google/Groq/Anthropic) | Funcionalidade do app | Não (opcional) |
| Histórico de compras | Coletado (Google Play) | Funcionalidade do app | — |
Marque: dados **criptografados em trânsito** (HTTPS) e **o usuário pode pedir exclusão** (botão Apagar todos os dados).

## 6. Teste fechado (contas pessoais criadas depois de nov/2023)
1. Gere o AAB: aba **Actions** do GitHub → última execução verde → artefato `manager-coach-N` → `app-release.aab`.
2. Play Console → **Testes → Teste fechado** → criar faixa → enviar o AAB → adicionar **pelo menos 12 testadores** (lista de e-mails ou Grupo do Google).
3. Os testadores precisam **instalar e manter** o app por **14 dias seguidos**. Comunidades de jogadores do jogo são um bom lugar para chamar testadores.
4. Depois dos 14 dias, o Play Console libera o pedido de **acesso à produção** (responde um questionário sobre o teste).

## 7. Produção
1. **Produção → Criar versão** → mesmo AAB (ou um mais novo; o `versionCode` é o número da execução do CI e sempre aumenta).
2. Lançamento gradual: 10% → 50% → 100%.
3. A análise do Google leva de algumas horas a 7 dias.

## Atualizações
Todo push gera um AAB novo nas Actions. Envie para a faixa de teste, confira e promova para produção.

## Riscos que continuam
- **Termos do jogo**: confira os termos de uso do jogo sobre ferramentas de terceiros. O app só lê a tela, não automatiza nada.
- **Marca**: não use o nome nem o logo do jogo no título, no ícone ou nas imagens principais da loja. Na descrição, só no aviso “não é afiliado”.
- **Idioma**: a leitura foi feita com o jogo em português. Em outro idioma várias telas não serão lidas.
