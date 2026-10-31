// 译表运行时回归（engine/src/main/assets/__translation_runtime.js）。
//
// 用 Node 仿真页面全局，跑**真实资产 JS**：4 个场景覆盖 hook 挂载点、
// 延迟数据表、标题菜单重绘、消息块拆行；另加资产健全性与重复注入幂等用例。
// 接入 Gradle jsHarness 生命周期（engine/build.gradle），防止随演进静默失效。
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const assetPath = path.join(__dirname, '..', '..', 'main', 'assets', '__translation_runtime.js');

let pass = 0;
let fail = 0;

function check(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  if (ok) { pass++; console.log(`  ok   ${name}`); }
  else { fail++; console.log(`  FAIL ${name}\n       got      ${JSON.stringify(actual)}\n       expected ${JSON.stringify(expected)}`); }
}

function makeContext() {
  const sandbox = {
    setInterval: () => 0,
    setTimeout: () => 0,
    // 场景函数序列化后在 vm 上下文内执行，断言经全局通道回到 Node 侧
    check,
  };
  sandbox.window = sandbox;
  return vm.createContext(sandbox);
}

// 每个场景一个独立 vm 上下文：setup 在运行时注入前执行（引擎类定义时序），
// run 在注入后执行（灌表 + 断言）。
function runScenario(name, assetSource, setup, run) {
  console.log(`\n[${name}]`);
  const ctx = makeContext();
  if (setup) vm.runInContext(`(${setup.toString()})(this);`, ctx);
  vm.runInContext(assetSource, ctx, { filename: '__translation_runtime.js' });
  vm.runInContext(`(${run.toString()})(this);`, ctx, { filename: `${name}.js` });
}

// ---- 场景 0：资产健全性与幂等 ----
{
  console.log('\n[asset sanity]');
  const source = fs.readFileSync(assetPath, 'utf8');
  check('资产存在且非空', source.length > 10000, true);
  check('含幂等门闩', source.includes('__TRS_RUNTIME__'), true);
  check('含灌表接口', source.includes('__TRS_SET_TRANSLATIONS__'), true);
  check('含写回开关前导', source.includes('__TRS_WRITEBACK__'), true);
  check('含状态接口', source.includes('__TRS_STATUS__'), true);

  // 重复注入（onPageStarted 早注入 + onPageFinished 补注入）不抛错且行为正常
  const ctx = makeContext();
  vm.runInContext(source, ctx);
  vm.runInContext(source, ctx);
  vm.runInContext(`(function (g) {
    g.Bitmap = function () {};
    g.Bitmap.prototype.drawText = function (text) { return text; };
    g.Window_Base = function () {};
  })(this);`, ctx);
  vm.runInContext(source, ctx);
  const status = vm.runInContext(
    'window.__TRS_SET_TRANSLATIONS__({ Attack: "攻击" }); new window.Window_Base().constructor;', ctx);
  const drawn = vm.runInContext('var w = new window.Bitmap(); w.drawText("Attack");', ctx);
  check('三次注入后仍可翻译', vm.runInContext('window.__TRS_STATUS__().pairs', ctx), 1);
  check('幂等注入不抛错', drawn, '攻击');
  void status;
}

// ---- 场景 1：挂载点 + 数据写回 + 热更新 ----
runScenario('hook sites', fs.readFileSync(assetPath, 'utf8'),
  function setup(g) {
    class Window_Base {
      prepareWordWrapEscapeCharacters(text) { return 'W[' + text + ']'; }
      sliceText(text, width) { return [text, width]; }
      processAutoColorWords(text) { return 'C[' + text + ']'; }
      drawTextEx(text) { return 'D[' + text + ']'; }
      convertEscapeCharacters(text) { return text; }
      obtainEscapeCode(text) { return text; }
    }
    class Bitmap {
      drawTextWithBitmapFont(text) { return text; }
      measureTextWidth(text) { return text.length * 10; }
    }
    class Game_Message {
      addText(text) { this._texts = (this._texts || []).concat([text]); }
      add(text) { this.addText(text); }
      applyTextReplace(text) { return 'AP[' + text + ']'; }
      allText() { return (this._texts || []).join('\n'); }
    }
    class Window_BattleLog { addText(t) { this.last = t; } push(t) { this.last = t; } }
    class Window_TitleCommand { arinaDrawSpaced(t) { return t; } }
    class Sprite_Name { setText(t) { this.t = t; } }
    class Window_Message { startMessage() { return true; } }
    g.Window_Base = Window_Base;
    g.Bitmap = Bitmap;
    g.Game_Message = Game_Message;
    g.Window_BattleLog = Window_BattleLog;
    g.Window_TitleCommand = Window_TitleCommand;
    g.Window_Message = Window_Message;
    g.Sprite_Name = Sprite_Name;
    g.insertLineBreaks = (t) => t;
    g.PluginUtils = { splitText: (t) => t.split('') };
    g.PIXI = { Text: class { constructor() { this._text = ''; } updateText() { this.rendered = 'R[' + this._text + ']'; } } };
    g.$dataActors = [null, { name: 'Hero', nickname: 'H', profile: 'A brave one', description: '' }];
    g.$dataItems = [null, { name: 'Potion', description: 'Restores HP' }];
    g.$dataSystem = { gameTitle: 'Test Game', terms: { basic: ['Level', 'HP'], commands: ['Fight'] } };
  },
  function run(g) {
    const map = { Attack: '攻击', Potion: '药水', Hero: '英雄', 'Restores HP': '恢复生命', Fight: '战斗', 'Test Game': '测试游戏', '\\C[2]Attack': '攻击2', 'Hello there\nHow are you': '你好\n你好吗' };
    const status = g.window.__TRS_SET_TRANSLATIONS__(map);

    check('prepareWordWrapEscapeCharacters', new g.Window_Base().prepareWordWrapEscapeCharacters('Attack'), 'W[攻击]');
    check('sliceText 首参', new g.Window_Base().sliceText('Attack', 100)[0], '攻击');
    check('processAutoColorWords', new g.Window_Base().processAutoColorWords('Attack'), 'C[攻击]');
    check('drawTextEx 首参', new g.Window_Base().drawTextEx('Attack'), 'D[攻击]');
    check('measureTextWidth 按译文计宽', new g.Bitmap().measureTextWidth('Potion'), 20);
    check('PluginUtils.splitText', g.PluginUtils.splitText('Potion').join(''), '药水');
    check('TAIMA Sprite_Name.setText', (() => { const s = new g.Sprite_Name(); s.setText('Hero'); return s.t; })(), '英雄');
    check('PIXI updateText', (() => { const t = new g.PIXI.Text(); t._text = 'Attack'; t.updateText(); return t.rendered; })(), 'R[攻击]');

    const msg = new g.Game_Message();
    msg.addText('Potion');
    check('消息缓冲累积译文', msg._texts, ['药水']);

    check('数据写回 $dataActors.name', g.$dataActors[1].name, '英雄');
    check('数据写回 $dataActors.profile', g.$dataActors[1].profile, 'A brave one');
    check('数据写回 $dataItems.description', g.$dataItems[1].description, '恢复生命');
    check('数据写回 $dataSystem.gameTitle', g.$dataSystem.gameTitle, '测试游戏');
    check('数据写回 terms.basic[0]', g.$dataSystem.terms.basic[0], 'Level');
    check('数据写回 terms.commands[0]', g.$dataSystem.terms.commands[0], '战斗');

    check('二次翻译防护', new g.Window_Base().prepareWordWrapEscapeCharacters('攻击'), 'W[攻击]');
    check('ESC 转义归一化', new g.Window_Base().drawTextEx('\u001bC[2]Attack'), 'D[攻击2]');

    const blockMsg = new g.Game_Message();
    blockMsg._texts = ['Hello there', 'How are you'];
    check('整段消息优先匹配', blockMsg.allText(), '你好\n你好吗');
    const lineMsg = new g.Game_Message();
    lineMsg._texts = ['Attack', 'unknown line'];
    check('逐行回退匹配', lineMsg.allText(), '攻击\nunknown line');

    // 热更新（Kotlin 侧 FileObserver 重推 __TRS_SET_TRANSLATIONS__ 的等价场景）
    g.window.__TRS_SET_TRANSLATIONS__({ Hero: '勇者', Attack: '攻击力' });
    check('热更新重算数据表', g.$dataActors[1].name, '勇者');
    check('热更新恢复未译文', g.$dataSystem.gameTitle, 'Test Game');
    check('热更新后绘制层生效', new g.Window_Base().prepareWordWrapEscapeCharacters('Attack'), 'W[攻击力]');

    check('灌表统计 pairs', status.pairs > 0, true);
    check('灌表统计 hooks', status.hooks > 0, true);
    check('灌表统计 writebackSlots', status.writebackSlots > 0, true);
  });

// ---- 场景 2：注入时数据表未加载，之后补齐（移植 late_data_test.js）----
runScenario('late data', fs.readFileSync(assetPath, 'utf8'),
  function setup(g) {
    class Window_Base {
      drawText(text) { return 'D[' + text + ']'; }
      drawTextEx(text) { return 'D[' + text + ']'; }
    }
    class Bitmap { constructor() { this.drawn = []; } drawText(text) { this.drawn.push(text); } }
    class Window_TitleCommandMock {
      constructor() { this.draws = 0; this.contents = new Bitmap(); }
      refresh() { this.draws++; }
    }
    g.Window_Base = Window_Base;
    g.Bitmap = Bitmap;
    const scene = { children: [] };
    scene.children.push(new Window_TitleCommandMock());
    g.SceneManager = { _scene: scene };
    // 注意：$dataActors / $dataSystem 此刻故意不存在——这正是本场景前提
  },
  function run(g) {
    g.$dataActors = [null, { name: 'Hero' }];
    g.$dataSystem = { gameTitle: 'Test Game', terms: { commands: ['New Game', 'Continue'] } };
    const status = g.window.__TRS_SET_TRANSLATIONS__({ Hero: '英雄', 'New Game': '开始游戏', Continue: '继续游戏' });

    check('延迟出现的数据表被重新收集', g.$dataActors[1].name, '英雄');
    check('延迟出现的 terms 被写回', g.$dataSystem.terms.commands[0], '开始游戏');
    check('写回槽位数量非零', status.writebackSlots > 0, true);
    check('已绘制窗口被刷新重绘', g.SceneManager._scene.children[0].draws > 0, true);
    check('绘制路径生效', new g.Window_Base().drawText('Continue'), 'D[继续游戏]');
  });

// ---- 场景 3：标题菜单在引擎晚定义 + 晚灌表下仍切换译文（移植 title_test.js）----
runScenario('title menu', fs.readFileSync(assetPath, 'utf8'),
  null, // 引擎类在 run 阶段（运行时注入之后）才定义，等同真实 document-start 注入时序
  function run(g) {
    class Bitmap {
      constructor() { this.drawn = []; }
      drawText(text) { this.drawn.push(text); }
      measureTextWidth(text) { return text.length * 10; }
    }
    class Window_Base {
      constructor() { this.contents = new Bitmap(); }
      drawText(text) { this.contents.drawText(text); }
      refresh() {}
    }
    class Window_Command extends Window_Base {
      constructor() { super(); this._list = []; this.makeCommandList(); }
      makeCommandList() { this._list = []; }
      commandName(index) { return this._list[index].name; }
      refresh() { this.contents.drawn = []; this.makeCommandList(); this.drawAllItems(); }
      drawAllItems() { for (let i = 0; i < this._list.length; i++) this.drawItem(i); }
      drawItem(index) { this.drawText(this.commandName(index)); }
    }
    class Window_TitleCommand extends Window_Command {
      makeCommandList() {
        this._list = [];
        this.addCommand(TextManager.newGame, 'newGame');
        this.addCommand(TextManager.continue_, 'continue');
        this.addCommand(TextManager.options, 'options');
      }
      addCommand(name, symbol) { this._list.push({ name: name, symbol: symbol }); }
    }
    // 插件窗口：构造器名不带 Window_ 前缀，刷新扫描必须按鸭子类型命中
    function Foo() { this.contents = new Bitmap(); this.painted = []; }
    Foo.prototype.refresh = function () { this.painted.push('refreshed'); };
    Foo.prototype.drawText = function (text) { this.contents.drawText(text); };

    g.Bitmap = Bitmap;
    g.Window_Base = Window_Base;
    g.Window_Command = Window_Command;
    g.Window_TitleCommand = Window_TitleCommand;
    g.Utils = { RPGMAKER_NAME: 'MZ' };
    g.TextManager = {};
    Object.defineProperty(g.TextManager, 'newGame', { configurable: true, get: function () { return g.$dataSystem.terms.commands[0]; } });
    Object.defineProperty(g.TextManager, 'continue_', { configurable: true, get: function () { return g.$dataSystem.terms.commands[1]; } });
    Object.defineProperty(g.TextManager, 'options', { configurable: true, get: function () { return g.$dataSystem.terms.commands[2]; } });
    g.$dataSystem = { gameTitle: 'Mock Game', terms: { commands: ['New Game', 'Continue', 'Options'], basic: ['Level', 'HP'] } };

    const titleWindow = new Window_TitleCommand();
    const pluginWindow = new Foo();
    g.SceneManager = { _scene: { children: [titleWindow, pluginWindow] } };
    titleWindow.refresh();

    const status = g.window.__TRS_SET_TRANSLATIONS__({
      'New Game': '新建游戏', Continue: '继续游戏', Options: '设置', Attack: '攻击',
    });

    check('Page 识别为 MZ', status.runtime.engine, 'MZ');
    check('TextManager 取值已翻译', g.TextManager.newGame, '新建游戏');
    check('TextManager 第二个 getter', g.TextManager.continue_, '继续游戏');
    check('commandName 已翻译', titleWindow.commandName(2), '设置');
    check('数据表写回 terms.commands[0]', g.$dataSystem.terms.commands[0], '新建游戏');
    check('标题菜单重绘后是中文', titleWindow.contents.drawn, ['新建游戏', '继续游戏', '设置']);
    check('非 Window_* 名字的窗口也被刷新', pluginWindow.painted.length > 0, true);
    check('Bitmap.drawText 直达调用被翻译', (() => {
      const bitmap = new Bitmap();
      bitmap.drawText('Attack');
      return bitmap.drawn;
    })(), ['攻击']);
    check('绘制层不重复翻译', (() => {
      const base = new Window_Base();
      base.drawText('新建游戏');
      return base.contents.drawn;
    })(), ['新建游戏']);

    // 场景切换 + 重推译表（热更新）后新窗口仍重绘
    const lateWindow = new Window_TitleCommand();
    g.SceneManager._scene = { children: [lateWindow] };
    g.window.__TRS_SET_TRANSLATIONS__({ 'New Game': '新的游戏', Continue: '继续', Options: '选项' });
    check('换场景后重新绘制', lateWindow.contents.drawn, ['新的游戏', '继续', '选项']);
  });

// ---- 场景 4：消息块拆行（移植 block_slice_test.js）----
runScenario('block slice', fs.readFileSync(assetPath, 'utf8'),
  function setup(g) {
    class Bitmap { constructor() { this.drawn = []; } drawText(text) { this.drawn.push(text); } measureTextWidth(text) { return text.length * 10; } }
    class Window_Base {
      constructor() { this.contents = new Bitmap(); }
      drawText(text) { this.contents.drawText(text.split('\n')[0]); return text; }
      refresh() {}
    }
    class Window_Message extends Window_Base {}
    g.Bitmap = Bitmap;
    g.Window_Base = Window_Base;
    g.Window_Message = Window_Message;
  },
  function run(g) {
    // 整个消息框是一个键；其单行故意不在表里（合并提取器的产出形态）
    const box = 'Hello there\nHow are you?\nI am fine.';
    g.window.__TRS_SET_TRANSLATIONS__({
      [box]: '你好\n你好吗？\n我很好。',
      'Single line': '单独一行',
      'Untranslated': 'Untranslated',
    });

    const w = new g.Window_Base();
    check('整段键直接命中', w.drawText(box), '你好\n你好吗？\n我很好。');
    check('第 1 行由整段译文拆出', w.drawText('Hello there'), '你好');
    check('第 2 行由整段译文拆出', w.drawText('How are you?'), '你好吗？');
    check('第 3 行由整段译文拆出', w.drawText('I am fine.'), '我很好。');
    check('单行键照常命中', w.drawText('Single line'), '单独一行');
    check('未翻译键保持原文', w.drawText('Untranslated'), 'Untranslated');
    check('表外文本保持原文', w.drawText('Never seen'), 'Never seen');
  });

console.log('');
console.log(fail === 0 ? `TRANSLATION RUNTIME HARNESS: all ${pass} checks passed` : `TRANSLATION RUNTIME HARNESS: ${fail} / ${pass + fail} FAILED`);
process.exit(fail === 0 ? 0 : 1);
