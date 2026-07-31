# FirstAid Copilot ｜ Gemma 端侧 Agentic 能力梳理

> 面向路演 / PPT 的能力速览。
>
> 核心主张：在 FirstAid Copilot 里，**Gemma 不是「独自决策的大模型」，而是一个被规则编排的、分级协同的端侧 Agent 能力层**——该快的地方规则秒回，该聪明的地方 Gemma 异步补位，该精准的地方交给超小专用模型。

## 设计范式

```text
Medical flow is rule-driven    医疗流程由可审核的规则状态机决策
Interaction  is Gemma-driven   自然语言理解与话术由端侧 Gemma 负责
Execution    is Android-driven  UI / 语音 / 节拍 / 工具调用由 Android 执行
```

---

## 能力 1 ｜异步快慢协同（确定性先行 + Gemma 异步增强）

**一句话**：先用规则「秒回」稳住现场，再让 Gemma 异步补一句更贴心的话——**快稳 + 慢智，两者都要**。

### 三拍节奏

```text
用户开口提问（慌张）
  │
  ├─① 快答（~0ms）   规则即时回应 + 命中预渲染 TTS 缓存 → 几乎零延迟先出声
  │                   例（S7 按压中）：「我在，继续按压。」
  │
  ├─② 慢答（异步）   Gemma 受控生成「补一句」更具体的安抚/解释；只加一句、不替换、不阻塞
  │
  └─③ 守门兜底       超时 / 越界 / 预算用尽 → 回退确定性答案，绝不卡住
```

### 关键数字

- 关键语音路径 **p95 < 1000ms**；Gemma 补充答 **p95 < 3000ms**
- CPR 节拍器自维持，**永不等 Gemma**；`critical` 热路径永不等 Gemma
- ack 是固定标准句 → 预渲染进 TTS 缓存 → 首包近 **0ms**（否则要付 ~3.5s 实时合成）

**示例**：用户慌问「他还有救吗 / 会醒吗」

- 规则快答（确定性、安全）：「现在不能保证结果，但持续按压是在帮他维持血流。」
- Gemma 异步只允许「加一句非重复细节」，且过安全守卫才出口

**金句**：救命场景里「延迟 = 风险」，但又需要 AI 的共情与灵活——**快慢协同让「不抢话的即时反馈」和「更像人的回应」同时成立**。

> 代码锚点：`src/voice/liveDriver.js`（`createOpenQuestionAckProposal` / `detectOpenQuestion`）、Android `live/edge/EdgeOpenQuestion.kt`。

---

## 能力 2 ｜分级模型：把最关键的判断交给最小、最专的模型

**一句话**：不是所有理解都该丢给 2B 大模型；**关键判断用「超小专用 embedding 模型」，最快又最稳**。

### 三级理解栈

| 层 | 用什么 | 体量 | 干什么 |
| --- | --- | --- | --- |
| L0 | 正则 / 拼音 | 0 模型 | 闭集关键词、误听兜底（最快） |
| **L1** | **超小 embedding 模型** | **~MB 级** | **呼吸观察 NLU（最致命的判断）** |
| L2 | Gemma 4 E2B | ~2.4GB | 开放问答 / 话术润色 / 交接叙述（异步、可回退） |

**L1 怎么工作**（呼吸 NLU）

- 模型：`BAAI/bge-small-zh-v1.5` 中文小模型 → 转成 **MediaPipe TFLite TextEmbedder**
- 流程：用户中文 → embedding 向量 → 与 18 条「原型短语」算**余弦相似度** → 最近邻得到呼吸意图
- 不硬猜：相似度 < 0.68，或前两名分差 < 0.04（语义含糊）→ **主动要求澄清**（`clarify_breathing`）
- 闭集输出：`no_normal_breathing` / `normal_breathing` / `agonal_breathing` / …；模型缺失或失败 → **自动回退正则版**

**金句**：呼吸判断是全流程**最致命的误判点**（把「没有呼吸」听成「有呼吸」会害死人）。正则太脆、2B 太慢——**embedding 语义分类兼顾鲁棒与毫秒级速度，且只有 MB 级、可离线、可降级**。把最关键的判断，交给最小、最专的模型。

> 代码锚点：Android `live/edge/TfliteBreathingNluClassifier.kt`、`EdgeTinyNluResolver.kt`；导出脚本 `scripts/export_breathing_nlu_text_embedder.py`；说明 `docs/edge_breathing_nlu_runbook.md`。

---

## 能力 3 ｜其他值得一提的 Agentic 设计

- **决策权信封**：Gemma 只能在当前阶段 `allowed_intents` 白名单内换意图，否则只能「同意图润色」——**能说人话，但不能行医**。
- **端侧安全守卫**：每句 Gemma 输出先过 `GemmaSuiteAsserts` 评分器（禁诊断词、禁 CPR 中「停止按压」、长度、越权字段、JSON 可解析）才能出口。
- **单驱动 + 优先级队列**：端侧独占一个 `OnDeviceGemmaDriver`，按 `NLU > 开放问答 > 主动润色` 调度，低价值后台任务**不会饿死**交互请求。
- **默认 OFF、离线可用**：增强层全部 flag-gated，翻开才生效；Node 服务不可达也能纯端侧增强，且永不接管医疗流程。

---

## 一页速记（适合做总结页）

- Gemma 是**被规则编排的端侧能力层**，不是急救流程的决策者。
- **快慢协同**：规则秒回（~0ms）+ Gemma 异步慢答（p95 < 3s），关键路径 p95 < 1s。
- **分级模型**：正则 → 超小 embedding（呼吸 NLU）→ 2B Gemma，按重要性与延迟分工。
- **安全可信**：白名单 + 守卫 + 兜底，越界即拦截、超时即回退，离线也能用。
