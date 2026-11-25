# 翻译工作台 Gemini 协议接入实现说明

状态：TYN 自有的 Gemini Interactions API v1 adapter、服务档案入口与合成协议回归已实现。真实 Google API 账户、Android 网络与设备行为尚未验收。

## 协议边界

- 服务协议 ID 为 `gemini-interactions-v1`，与 `openai-compatible-chat-v1` 分开注册。它实现 Google Gemini Developer API 的 Interactions v1，不是将 Gemini 伪装成任意 OpenAI 兼容 URL，也不使用旧 `generateContent` API。
- 默认根地址为 `https://generativelanguage.googleapis.com`；用户也可填写 Gemini Interactions 兼容服务的根地址，adapter 在该根地址下访问 `/v1beta/models` 与 `/v1/interactions`。地址校验拒绝 user-info、query、fragment 和不支持的 URL 形式。Vertex AI 的 endpoint、认证和 project/location 配置仍未接入。
- API Key 使用 `x-goog-api-key` 请求头，不放入 URL、任务快照、日志或 Room 普通字段。请求 JSON 包含输入语言、目标语言、稳定条目 ID、服务提示词和结构化 JSON schema，并显式设置 `store=false`、`stream=false`。翻译与 AI 校对分别使用翻译提示词和校对提示词。
- 模型目录通过 Gemini API `GET /v1beta/models?pageSize=1000` 获取并按 `nextPageToken` 分页，仅显示模型目录标记支持 `generateContent` 的模型 ID。用户可手动填写模型 ID；模型目录不能证明账号/项目当前具有调用权限。
- 交互成功响应要求 `status=completed`，从 `steps` 的 `model_output` 文本提取 JSON，再交给共享结构化结果解析器。解析器按稳定 ID 拆分有效项与缺失、重复、未知、无效或格式损坏项；占位符/控制码与换行校验失败的条目不能成功提交。后台 Worker 保持既有逐批 Room 事务和检查点语义。
- 两种任务能力由 Gemini adapter 显式声明为 `TRANSLATE`、`AI_PROOFREAD`；只有档案启用、模型 ID 有效且 API Key 密钥引用存在时才视为已配置。失败不做自动重试，用户可以依照现有工作台规则重试失败/未完成条目。取消由翻译域 OkHttp transport 取消底层请求。

## 验证与限制

- `GeminiInteractionsAdapterTest` 使用合成 HTTP 响应覆盖请求路径、API Key header、无 query 鉴权、`store=false`、提示词分流、模型分页/能力过滤、结构化建议解析、鉴权错误映射和自定义 Gemini 服务根地址；`OpenAiCompatibleChatAdapterTest` 验证共享解析器继续覆盖稳定 ID、部分失败与格式令牌校验。
- 合成测试与 Android 构建不会证明真实账户权限、具体模型可用性、Google 服务端结构差异、配额/限流策略或真实游戏翻译质量。仍需真实 API 账户验证模型目录、翻译、校对、鉴权失败、限流、超时和取消；未在手机/平板上验收 UI、后台任务和网络行为。
- 该接入没有加入新的 SDK 或第三方依赖。本文记录 Gemini 接入时的单协议范围；后续七种机器翻译由独立 adapter 接入，当前仍未接入 Vertex AI 和本地翻译模型；Gemini 自定义服务根地址仅适用于实现相同 Interactions 协议的服务。完整当前服务清单以[服务配置与协议适配实现说明](翻译服务配置与协议适配实现说明.md)为准。

## 官方协议资料

- [Gemini Interactions API](https://ai.google.dev/api/interactions-api-v1)：REST `POST /v1/interactions`、结构化 `response_format`、response steps 与 `store` 请求字段。
- [Gemini API 模型列表](https://ai.google.dev/api/rest/v1beta/models/list)：`GET /v1beta/models`、分页参数和模型能力字段。
- [Gemini Interactions 概览](https://ai.google.dev/gemini-api/docs/interactions-overview)：新项目推荐的 API 说明。
