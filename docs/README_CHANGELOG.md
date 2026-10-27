# README updates / 首页更新记录

## 2026-10-27

- Use English for the default `README.md`; add `README.zh-CN.md` with reciprocal language links and matching core information.
- Reorganize the overview around feature status, reference screenshots, colored architecture diagrams, quick starts, and documentation links.
- Explicitly distinguish Emergency on `main` from Android/Web Practice on `codex/practice-copilot-training`; preserve simulated-input and model-readiness boundaries.
- Preserve the previous detailed Chinese README in `technical-guide.zh-CN.md`, adjusting its relative links and marking historical claims.
- Documentation-only change. No application source, model, dependency, release package, or feature-branch content was changed. Validation covers Markdown structure, local links/anchors, commands against repository manifests, and the published document content; no new runtime test or medical/device validation is claimed.
- The documented `npm run demo` mock replay was smoke-checked and reached `S9_HANDOVER`; this does not validate real speech, vision, inference, or devices. Full regression tests were not rerun for the documentation change.

中文说明：默认首页改为英文，保留中文首页；补充功能分支状态、原型截图、彩色架构图、启动说明和语言标识。原详细说明保留为技术参考。本轮仅修改文档，不代表新增功能或重新完成设备验收。
