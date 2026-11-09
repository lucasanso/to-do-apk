# TaskFlow — Intelligent Personal Productivity

Aplicativo Android nativo de produtividade pessoal: tarefas com priorização inteligente explicável, planejamento diário, hábitos com sequências, calendário, estatísticas, lembretes, widget e backup. **100% offline**, sem conta, sem internet, sem analytics.

> **APK pronto:** [`release/TaskFlow-1.0.0.apk`](release/TaskFlow-1.0.0.apk) (assinado em modo release, ~2,7 MB, Android 8.0+).

---

## Instalação no Galaxy S26

1. Copie `release/TaskFlow-1.0.0.apk` para o celular (cabo USB, Google Drive, e-mail para si mesmo etc.).
2. Abra o arquivo no app **Meus Arquivos**. Na primeira vez, o Android pede para permitir "Instalar apps desconhecidos" para o app usado para abrir — permita.
3. Se o **Auto Blocker** da Samsung estiver ativo (Configurações › Segurança e privacidade › Auto Blocker), desative-o temporariamente para instalar.
4. Ao abrir o TaskFlow, permita **notificações**. Para lembretes no minuto exato, vá em *Configurações do app › Notificações › Lembretes pontuais* e ative "Alarmes e lembretes".
5. Widget: segure a tela inicial › Widgets › TaskFlow · Prioridades.

Atualizações futuras precisam ser assinadas com a mesma chave; se a chave mudar, o Android exige desinstalar antes — exporte um backup em Configurações › Dados e importe depois.

---

## Funcionalidades

| Área | O que faz |
|---|---|
| **Início** | Saudação por horário, data, progresso diário (anel), pendentes / feitas hoje / atrasadas (tocáveis), **Foco de hoje** com motivo da recomendação, hábitos do dia, próximas tarefas, resumo da matriz de prioridades. |
| **Tarefas** | Título, descrição, categoria, prioridade (baixa → crítica), criação, prazo, horário, duração, status (pendente / em andamento / concluída / arquivada), tags, subtarefas, lembrete, recorrência e **dependências**. Criar, editar, excluir (com desfazer), duplicar, concluir, adiar, arquivar, promover subtarefa a tarefa. Deslize → conclui, ← adia. |
| **Captura rápida** | Botão + abre uma linha só. Entende `hoje`, `amanhã`, `seg…dom`, `25/12`, `19h`/`14:30`, `!alta`/`!1..!4`, `#tag`, `@categoria`, `30min`/`1h30m`/`2hrs`. Também por atalho do ícone (pressão longa) e pelo widget. |
| **Busca e filtros** | Busca sem acento em título, descrição, categoria, tags e subtarefas. Filtros: Hoje, Amanhã, Esta semana, Atrasadas, Sem prazo, Alta prioridade, Concluídas, Arquivadas + filtro por categoria + 4 ordenações. |
| **Priorização** | Pontuação 0–100 por regras locais, matriz urgente × importante, sinalizadores (urgente, importante, rápida, atrasada, adiada com frequência, bloqueada, desbloqueia outras) e cartão "Por que esta prioridade?" com cada fator e seus pontos. |
| **Meu dia** | Selecionar tarefas, ordem por **arrastar e soltar** (segure a alça; ações de acessibilidade "mover para cima/baixo"), tempo total vs. disponível, **alerta de sobrecarga**, iniciar/concluir, planejamento automático pelo ranking. |
| **Hábitos** | Separados das tarefas. Diário, dias específicos, semanal, mensal, a cada N dias. Histórico (últimos 14 dias editáveis), sequência atual, recorde e taxa de 30 dias. Pausar sem perder histórico. |
| **Agenda** | Mês e semana; marcadores de prazos, hábitos e concluídas; dia selecionado lista tudo e permite criar tarefa naquela data. Lê das mesmas tabelas (sem duplicação). |
| **Notificações** | Lembrete antes do prazo / no horário (com ações *Concluir* e *Adiar 1 dia*), aviso de atrasadas, resumo diário no horário escolhido, lembrete de hábito (ação *Feito*). Cada tipo pode ser desligado. Reagendadas após reiniciar o celular. |
| **Estatísticas** | Concluídas por dia (14 d) e por semana (8 sem), taxa de conclusão, evolução vs. semana anterior, volume e conclusão por categoria, tarefas mais adiadas, planejado × concluído. |
| **Categorias** | 9 iniciais (Universidade, Trabalho, Engenharia de Dados, Projetos pessoais, Finanças, Saúde e exercícios, Estudos, Casa, Outros), cor e ícone próprios, editáveis. |
| **Histórico** | Criação, edição (com resumo do que mudou), conclusão, reabertura, adiamento, arquivamento, exclusão, duplicação, promoção. Sobrevive à exclusão da tarefa. |
| **Backup** | Exporta/importa JSON pelo seletor de arquivos do Android (sem permissão de armazenamento). Importação valida formato, versão, enums, faixas, referências, duplicidades e ciclos **antes** de tocar no banco; aplica tudo numa transação (tudo ou nada). |
| **Widget** | Próximas tarefas prioritárias com botão de conclusão rápida, + para nova tarefa, toque abre a tarefa. |
| **Tema** | Claro, escuro ou sistema; opção Material You. Edge-to-edge. |

---

## 1. Arquitetura

MVVM + Clean Architecture em camadas, com dependências apontando para dentro:

```
presentation (Compose + ViewModels/StateFlow)
      │ usa
      ▼
domain  (modelos puros, regras, use cases, interfaces de repositório)   ◄── sem Android
      ▲ implementa
      │
data    (Room, DataStore, backup JSON)      notifications / widget (AlarmManager, WorkManager, Glance)
```

Decisões:

- **Domínio puro em Kotlin** (`domain/engine`): priorização, recorrência, sequências, prazos, filtros, estatísticas e parser da captura rápida não dependem de Android → testáveis em JVM e determinísticos (`TimeProvider` injetável).
- **Use cases** para toda mutação de tarefa (`SaveTask`, `SetTaskDone`, `Postpone`, `Delete`/`Restore`, `Duplicate`, `PromoteSubtask`…). Cada um valida, grava, registra histórico e sincroniza lembretes no mesmo fluxo → editar/excluir/concluir não deixa estado inconsistente.
- **Erros centralizados**: `AppResult`/`AppError` (`core/AppResult.kt`); a UI mostra a mensagem via snackbar.
- **Reatividade**: Room expõe `Flow`; ViewModels combinam fluxos em um `StateFlow` de estado de tela.
- **Hilt** para DI, inclusive em Workers (`@HiltWorker`), BroadcastReceivers e widget (`EntryPoint`).
- **Lembretes pontuais com AlarmManager** (WorkManager não garante minuto exato); **WorkManager** para tarefas periódicas (resumo diário, verificação de atrasadas).
- **Um módulo Gradle com pacotes por camada**. Avaliei separar em módulos Gradle (`:core`, `:domain`, `:data`…); para um app pessoal isso aumenta tempo de build e configuração sem ganho real agora. Os pacotes já respeitam as fronteiras (domain não importa data/presentation), então a extração para módulos é mecânica quando fizer sentido.

## 2. Estrutura de diretórios

```
app/src/main/java/com/taskflow/
├── core/              AppResult/AppError, TimeProvider
├── domain/
│   ├── model/         Task, Subtask, Category, Habit, RecurrenceRule, HistoryEntry…
│   ├── engine/        PriorityEngine, RecurrenceCalculator, HabitStreaks, DueRules,
│   │                  TaskFilters, ProductivityCalculator, QuickCaptureParser
│   ├── repository/    interfaces (TaskRepository, …, ReminderScheduler)
│   └── usecase/       TaskUseCases, OtherUseCases
├── data/
│   ├── local/         Entities, DAOs, TaskFlowDatabase, Mappers
│   ├── repository/    implementações Room
│   ├── settings/      DataStore (SettingsRepository)
│   └── backup/        DTOs, BackupValidator, BackupManager
├── di/                módulos Hilt
├── notifications/     Notifier, AlarmReminderScheduler, Receivers, Workers, ReminderSync
├── widget/            Glance widget + ação de concluir
├── navigation/        Routes, TaskFlowNavHost (barra inferior, FAB, captura rápida)
└── presentation/      theme, components, dashboard, tasks, taskdetail, taskedit,
                       myday, habits, calendar, stats, categories, settings, history, quickadd
app/src/test/…         testes de domínio, use cases, Room/backup (Robolectric) e UI
app/schemas/           schema exportado do Room (base para migrações futuras)
```

## 3. Modelo de dados (Room, v1)

```
categories(id, name UNIQUE, colorArgb, icon, position)
tasks(id, title, description, categoryId→categories SET NULL, priority, status,
      createdAt, updatedAt, dueDate[epochDay], dueTime[min], originalDueDate,
      estimatedMinutes, completedAt, startedAt, postponeCount, reminderMinutesBefore, recurrence)
subtasks(id, taskId→tasks CASCADE, title, done, position)
tags(id, name UNIQUE)            task_tags(taskId→tasks CASCADE, tagId→tags CASCADE)
task_dependencies(taskId→tasks CASCADE, dependsOnId→tasks CASCADE)
habits(id, title, description, categoryId→categories SET NULL, rule, reminderTime, createdAt, startDate, archived)
habit_logs(habitId→habits CASCADE, date)          -- uma linha por dia cumprido
day_plan(date, taskId→tasks CASCADE, position)     -- "Meu dia"
day_meta(date, availableMinutes)
history(id, taskId [sem FK, sobrevive à exclusão], taskTitle, type, details, timestamp)
```

- Recorrência de **tarefa**: ao concluir, cria a próxima ocorrência (nova linha), preservando o histórico de cada execução. **Hábito**: uma regra + log de datas, sem gerar tarefas — por isso não se misturam.
- `originalDueDate` guarda o primeiro prazo; `postponeCount` conta adiamentos → base para "atraso em relação ao prazo original" e "constantemente adiadas".
- Tags órfãs são removidas automaticamente; dependências circulares são bloqueadas na gravação e na importação.

## 4. Fluxo de navegação

```
Barra inferior: Início · Tarefas · Meu dia · Hábitos · Agenda      (+ FAB: captura rápida / novo hábito)
Início ──► detalhe da tarefa ──► editar
   └─ menu ⋮ ─► Estatísticas · Categorias · Histórico · Configurações
Tarefas (busca/filtros) ─► detalhe ─► editar | dependências ─► outra tarefa
Agenda ─► dia ─► detalhe / nova tarefa na data
Notificação / widget / atalho ─► deep link para tarefa, filtro de atrasadas, hábitos ou captura rápida
```

## 5. Priorização inteligente

`domain/engine/PriorityEngine.kt` — pontuação por regras, limitada a 0–100, cada fator vira uma linha legível:

| Fator | Pontos |
|---|---|
| **Importância** (prioridade do usuário) | baixa 8 · média 18 · alta 30 · crítica 40 |
| **Urgência** (proximidade do prazo) | atrasada 30 + 2/dia (máx. +10) · hoje 26 (30 se faltam ≤ 2 h) · amanhã 20 · ≤ 3 d 13 · ≤ 7 d 7 · ≤ 14 d 3 · depois 1 |
| **Duração** | ≤ 15 min +6 (*rápida*) · ≤ 30 min +3 · ≥ 4 h sem urgência −3 |
| **Tempo pendente** | +1 a cada 3 dias (máx. +6) |
| **Dependências** | desbloqueia N abertas: +4·N (máx. +12) · aguardando dependência aberta: −25 (*bloqueada*, vai para o fim) |
| **Adiamentos / deslocamento** | +2 por adiamento (máx. +10; ≥ 3 = *adiada com frequência*) · +1 por dia que o prazo deslizou do original (máx. +6) |

**Urgência ≠ importância**: a classificação usa a matriz de Eisenhower calculada separadamente — *urgente* = atrasada ou vence até amanhã; *importante* = prioridade alta/crítica → **Fazer agora**, **Planejar**, **Resolver rápido**, **Depois**.
**Foco de hoje** = até 5 tarefas não bloqueadas que estão no "Meu dia", vencem hoje/atrasadas, são "Fazer agora" ou pontuam ≥ 50.

## 6. Etapas executadas

1. **Planejamento** — arquitetura, entidades, banco, navegação e design system (este documento).
2. **MVP** — Room + repositórios, use cases, priorização, Início, CRUD completo, categorias, Meu dia. Compilação validada.
3. **Produtividade** — recorrências, hábitos e sequências, agenda mês/semana, notificações (AlarmManager + WorkManager), estatísticas.
4. **Avançado** — widget Glance, backup/restauração validados, histórico, captura rápida com linguagem natural, atalho, desfazer exclusão, acessibilidade.
5. **Testes e distribuição** — 68 testes automatizados, correção dos bugs encontrados, APK release com R8 e assinatura.

---

## Compilação

Requisitos: JDK 17+ e Android SDK (platform 35, build-tools 35). Android Studio Ladybug ou mais recente abre o projeto diretamente.

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:assembleRelease
```

O APK sai em `app/build/outputs/apk/release/app-release.apk`.

**Assinatura:** o build release lê `keystore.properties` na raiz (fora do Git, ver `.gitignore`):

```properties
storeFile=keystore/taskflow-release.jks
storePassword=...
keyAlias=taskflow
keyPassword=...
```

Sem esse arquivo, o release é assinado com a chave de debug (instala normalmente, mas não atualiza por cima de um APK assinado com outra chave). O `release/TaskFlow-1.0.0.apk` foi assinado com uma chave release gerada no ambiente de build; para publicar atualizações por cima dele é preciso guardar essa mesma chave. Gerar uma nova:

```bash
keytool -genkeypair -keystore keystore/taskflow-release.jks -alias taskflow -keyalg RSA -keysize 4096 -validity 36500
```

## Testes

| Suíte | Cobre |
|---|---|
| `PriorityEngineTest` | cada fator, quadrantes, rápidas, adiadas, bloqueio/desbloqueio, ranking, foco de hoje |
| `RecurrenceTest` | diária/intervalo, dias da semana, semanal, mensal (dia 31 → fev 28), N dias, codificação, sequências e taxa |
| `ProductivityAndRulesTest` | regras de vencimento (data + hora), semana, filtros, busca, relatório, progresso diário |
| `QuickCaptureParserTest` | todos os tokens da captura rápida |
| `TaskUseCasesTest` | validação, prazo original, ciclo de dependências, lembretes, conclusão/reabertura idempotentes, recorrência, adiamento, exclusão + desfazer, duplicação, promoção de subtarefa, captura rápida |
| `BackupValidatorTest` | formato, versão, referências, valores inválidos, duplicidades, ciclos |
| `RoomRepositoryTest` (Robolectric) | persistência real com cascatas, categoria excluída, ordem do plano, backup ida-e-volta, importação inválida não altera dados |
| `AppSmokeTest` (Robolectric + Compose) | sobe o app real e percorre captura rápida, detalhe, conclusão, todas as abas, criação de hábito, telas secundárias, edição, Meu dia, adiar e excluir |

## Privacidade e permissões

Sem permissão de **INTERNET** — o app não tem como transmitir dados. Backup do Android/transferência entre aparelhos desativados; o backup é só o JSON que você exporta. Permissões pedidas: notificações, alarmes exatos (opcional), reagendar após reinício e vibração. `ACCESS_NETWORK_STATE`/`FOREGROUND_SERVICE` aparecem no manifesto final porque vêm da biblioteca WorkManager; não são usadas e não dão acesso à rede.

## Limitações conhecidas

- Validado em testes automatizados (incluindo o app completo sob Robolectric), mas **não executado num aparelho físico** durante o desenvolvimento — vale um teste rápido de notificações e widget no S26.
- Sem "Alarmes e lembretes" permitido, o Android pode atrasar lembretes alguns minutos (modo econômico).
- O widget se atualiza a cada alteração enquanto o app está em memória, ao concluir pelo próprio widget/notificação e a cada 30 min.
- Interface somente em português (pt-BR).
