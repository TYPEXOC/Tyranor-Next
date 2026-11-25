# Third-party notices

本文件记录代码/资产级第三方声明；引擎运行库与静态依赖的完整许可文本见 [README](README.md) 许可证表及各 `engine/*/licenses/` 目录。

## libjpeg-turbo（IJG / BSD-3-Clause）

System4 运行时内的 JPEG 编解码使用 libjpeg-turbo（IJG 许可 + Modified BSD 许可）。按 IJG 许可的分发条件，产品文档须包含以下声明：

> This software is based in part on the work of the Independent JPEG Group.

- IJG README（分发时必须原样保留）：[engine/xsystem4/licenses/README.ijg](engine/xsystem4/licenses/README.ijg)
- 上游许可说明与 Modified (3-clause) BSD 文本：[engine/xsystem4/licenses/libjpeg-turbo-LICENSE.md](engine/xsystem4/licenses/libjpeg-turbo-LICENSE.md)

## mochi-compose (MochiToast)

The in-app notice capsule (`app/src/main/java/com/tyranor/next/ui/common/notice/NoticeHost.kt`) follows the
interaction and motion design of `MochiToast` from **mochi-compose** by the mochi-compose contributors:
a capsule that grows out of a small top-center "island", continuous size morphing between consecutive notices
with blurred icon/text replacement, press to enlarge / glow / pause the timer, flick up to dismiss and a
rubber-banded downward pull.

- Project: [mochi-compose](https://github.com/feice11/mochi-compose)
- License: Apache License 2.0
- Copyright 2026, mochi-compose contributors

The Tyranor-Next implementation is a re-implementation on this project's own motion tokens, notice queue
(`NoticeCenter`) and theme system; constants such as the island size (120 x 36 dp), the press scale (1.04),
the follow distance (4 dp), the dismiss distance / velocity (24 dp / 600 px/s) and the exit timing (190 ms) are
taken from the original design. The original license text is available at the project URL above.

## Miuix (determinate progress indicator)

The drawing in `MotionLinearProgressIndicator.kt` is adapted from
`ProgressIndicator.kt` in [Miuix](https://github.com/compose-miuix-ui/miuix) 0.9.2.
Copyright 2025, compose-miuix-ui contributors. License: Apache License 2.0.

## SmartSub（翻译服务图标）

`app/src/main/res/drawable/ic_translation_provider_{aliyun,azure,baidu,bing,deepl,deepseek,doubao,gemini,google_web,openai,tencent,volcengine,xunfei}.xml` 是 SmartSub `renderer/public/images/providers/` 中对应 SVG 图标的 Android VectorDrawable 适配；`app/src/main/res/drawable-nodpi/ic_translation_provider_niutrans.png` 与 `ic_translation_provider_youdao.png` 也取自同一目录。来源固定为 [SmartSub commit a03d73c](https://github.com/buxuku/SmartSub/tree/a03d73c79a5a21513092996a9ad715ee68e57368/renderer/public/images/providers)，许可为 [MIT License](https://github.com/buxuku/SmartSub/blob/a03d73c79a5a21513092996a9ad715ee68e57368/LICENSE)，Copyright (c) 2024 Lin Xiaodong。`ic_translation_provider_google_cloud.xml` 与 `ic_translation_provider_anthropic.xml` 根据 [simple-icons commit 32e0300](https://github.com/simple-icons/simple-icons/tree/32e030086fdc605196391aab28519b14dc4295b4/icons) 中的 `googlecloud.svg`、`anthropic.svg` 转为 Android VectorDrawable；该项目图标许可为 [CC0 1.0](https://github.com/simple-icons/simple-icons/blob/32e030086fdc605196391aab28519b14dc4295b4/LICENSE.md)。`ic_translation_provider_custom_api.xml` 是 TYN 自绘的通用代码接口图标，不代表具体服务商。机器翻译服务协议 adapter 为 Tyranor-Next 独立实现，没有复制 SmartSub 源码或依赖。

```text
MIT License

Copyright (c) 2024 Lin Xiaodong

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
