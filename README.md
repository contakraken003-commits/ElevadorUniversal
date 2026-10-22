# Elevador Universal — Etapa 2

Plugin inicial para Paper/Spigot com itens especiais de elevador, validação de colocação, teleporte e persistência.

## Requisitos
- JDK 21
- Gradle 8.10+ ou abrir o projeto pelo IntelliJ IDEA para importar o Gradle
- Servidor Paper/Spigot para testes
- Geyser/Floodgate para permitir jogadores Bedrock entrarem no servidor Java

## Compilar
Na pasta do projeto:
```powershell
.\gradlew.bat clean build
```
O JAR será gerado em `build/libs/ElevadorUniversal-0.2.0.jar`.

## Teste
1. Instale o JAR na pasta `plugins` de um servidor de teste.
2. Reinicie o servidor.
3. Dê permissão de operador a um jogador ou use o console.
4. Execute `/elevador give <jogador>`.
5. Coloque o bloco verde numa posição inferior e o vermelho acima, no mesmo chunk e na mesma faixa vertical de 64 blocos.
6. Fique completamente sobre o verde e pule para subir; fique completamente sobre o vermelho e agache para descer.

## Regras implementadas
- Um elevador por combinação mundo + chunk + faixa de 64 blocos.
- Verde sempre abaixo do vermelho.
- Os dois blocos devem ficar no mesmo chunk e na mesma faixa vertical.
- Itens vinculados ao UUID do comprador.
- Proteção contra quebra por terceiros.
- Teleporte com verificação de espaço livre.
- Salvamento dos elevadores em `plugins/ElevadorUniversal/elevators.yml`.
- Comando de entrega que pode ser chamado por sistemas de loja que aceitam comandos do servidor.

## Proteções e persistência nesta correção
- Explosões não removem blocos registrados como elevadores.
- Pistões não podem mover blocos registrados como elevadores.
- Eventos de alteração de bloco por entidades são cancelados quando atingem um bloco registrado.
- Registros vinculados a mundos ainda não carregados são preservados no YAML e recuperados quando o mundo carrega.
- O salvamento usa arquivo temporário e substituição para reduzir o risco de corromper o YAML durante a gravação.
- Elevadores parciais do mesmo proprietário em faixas verticais diferentes não são bloqueados apenas por estarem no mesmo chunk.

## Limitações desta etapa
- Os itens usam concreto verde/vermelho com nome e lore personalizados. Texturas próprias exigem um resource pack e configuração adicional para clientes Java e Bedrock.
- A entrega `/elevador give <jogador>` é uma ponte de integração por comando; ainda não existe cobrança de dinheiro automática. A loja deve executar o comando após a compra.
- A detecção do pulo via movimento e a detecção do agachamento devem ser testadas em Paper/Spigot e com Geyser, porque os eventos de movimento podem variar entre versões.
- Esta correção de código ainda precisa ser compilada no ambiente do projeto e testada num servidor de desenvolvimento antes de produção. A compilação anterior do projeto original não valida estas alterações.
