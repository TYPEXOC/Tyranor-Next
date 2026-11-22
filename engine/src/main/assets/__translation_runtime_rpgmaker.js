  // Engine-specific source module inserted into __translation_runtime.js by WebTranslationRuntime.
  root.__TRS_ENGINE__ = 'RPG Maker';
  // Every TextManager accessor is a getter over $dataSystem.terms/words
  // (TextManager.newGame -> terms.commands[0]). Wrapping the getters keeps the
  // title menu, menu labels and messages correct even when the game reads the
  // term before the writeback pass has run.
  function patchTextManager() {
    var manager = root.TextManager;
    if (!manager) return;
    var names;
    try { names = Object.getOwnPropertyNames(manager); } catch (_) { return; }
    for (var i = 0; i < names.length; i++) {
      var name = names[i];
      var descriptor;
      try { descriptor = Object.getOwnPropertyDescriptor(manager, name); } catch (_) { continue; }
      if (!descriptor || typeof descriptor.get !== 'function' || !descriptor.configurable) continue;
      if (descriptor.get.__trsWrapped) continue;
      var getter = descriptor.get;
      var wrapped = (function (label, source) {
        return function () {
          var value = source.call(this);
          return typeof value === 'string' ? tr(value, 'textmanager:' + label) : value;
        };
      })(name, getter);
      try {
        Object.defineProperty(wrapped, '__trsWrapped', {value: true});
        Object.defineProperty(manager, name, {
          configurable: true,
          enumerable: descriptor.enumerable,
          get: wrapped,
          set: descriptor.set
        });
        stats.installed++;
        HOOK_SITES.push('textmanager:' + name);
      } catch (_) {}
    }
    // The accessor flavours (TextManager.basic(1), .command(0), ...) return the
    // string instead of being getters, so they need the result wrapper.
    var methods = ['basic', 'param', 'command', 'message', 'partyMember'];
    for (var m = 0; m < methods.length; m++) {
      wrapResult(manager, methods[m], 'textmanager:' + methods[m]);
    }
  }

  // Point 7: PIXI.Text.updateText reads this._text, so the text field itself
  // has to be rewritten before the glyph atlas is rebuilt.
  function patchPixiUpdateText() {
    var pixiText = root.PIXI && root.PIXI.Text;
    var proto = pixiText && pixiText.prototype;
    if (!proto || typeof proto.updateText !== 'function' || isPatched(proto.updateText)) return;
    var original = proto.updateText;
    var wrapped = function () {
      if (typeof this._text === 'string' && this._text) {
        var next = tr(this._text, 'pixi-updateText');
        if (next !== this._text) this._text = next;
      }
      return original.apply(this, arguments);
    };
    try {
      Object.defineProperty(wrapped, '__trsOriginal', {value: original});
      proto.updateText = wrapped;
      patched.push(wrapped);
      stats.installed++;
      HOOK_SITES.push('pixi-updateText');
    } catch (_) {}
  }

  // Points 2/3: TAIMA registers Sprite_Name / Sprite_EventName style classes
  // on window at plugin load time, so they are matched by constructor name.
  var nameSpriteTick = 0;
  function patchNameSprites() {
    var pattern = /(Sprite_?(Name|EventName)|(Name|EventName)Sprite|EventNameText|nameSprite)/i;
    var keys;
    try { keys = Object.keys(root); } catch (_) { return; }
    for (var i = 0; i < keys.length; i++) {
      var key = keys[i];
      var value;
      try { value = root[key]; } catch (_) { continue; }
      if (typeof value !== 'function' || !value.prototype) continue;
      if (!pattern.test(key) && !pattern.test(value.name || '')) continue;
      wrapArg0(value.prototype, 'setText', 'taima-name:' + key);
    }
  }

  // Engine/plugin level lookup points installed on MV and MZ. Missing
  // plugins and missing methods are skipped so the same set works on a bare
  // project and on a heavily patched one.
  function patchRpgMakerDeep() {
    var windowBase = root.Window_Base && root.Window_Base.prototype;
    if (windowBase) {
      wrapArg0(windowBase, 'prepareWordWrapEscapeCharacters', 'rpg-prepareWordWrap');
      wrapArg0(windowBase, 'sliceText', 'rpg-sliceText');
      wrapArg0(windowBase, 'processAutoColorWords', 'rpg-autoColorWords');
      wrapArg0(windowBase, 'drawTextRuby', 'rpg-drawTextRuby');
      wrapArg0(windowBase, 'drawTextEx', 'rpg-drawTextEx');
      wrapArg0(windowBase, 'convertEscapeCharacters', 'rpg-convertEscape');
      wrapArg0(windowBase, 'obtainEscapeCode', 'rpg-obtainEscapeCode');
      wrapArg0(windowBase, 'createTextState', 'rpg-createTextState');
    }
    var battleLog = root.Window_BattleLog && root.Window_BattleLog.prototype;
    if (battleLog) {
      wrapArg0(battleLog, 'addText', 'rpg-battleLog');
      wrapArg0(battleLog, 'push', 'rpg-battleLogPush');
    }
    // Game_Message input methods store their arguments in the message queue.
    // Keep those source strings intact so map updates can resolve the original
    // key again; Game_Message.allText and the draw hooks translate on read.
    var titleCommand = root.Window_TitleCommand && root.Window_TitleCommand.prototype;
    if (titleCommand) wrapArg0(titleCommand, 'arinaDrawSpaced', 'rpg-arinaDrawSpaced');
    if (root.Window_TitleCommand) wrapArg0(root.Window_TitleCommand, 'arinaDrawSpaced', 'rpg-arinaDrawSpaced');
    var bitmap = root.Bitmap && root.Bitmap.prototype;
    if (bitmap) {
      // Bitmap.drawText is the lowest level text call. Plugins that draw their
      // own menus call contents.drawText() directly and never reach the
      // Window_Base hooks above, so this one is what keeps custom UIs covered.
      wrapArg0(bitmap, 'drawText', 'rpg-bitmapDrawText');
      wrapArg0(bitmap, 'drawTextWithBitmapFont', 'rpg-bitmapFontText');
      wrapArg0(bitmap, 'measureTextWidth', 'rpg-measureTextWidth');
    }
    // Command windows keep the label in this._list[index].name, so the string
    // is produced by commandName() and then handed to drawText.
    var commandWindow = root.Window_Command && root.Window_Command.prototype;
    if (commandWindow) {
      wrapResult(commandWindow, 'commandName', 'rpg-commandName');
    }
    var messageWindow = root.Window_Message && root.Window_Message.prototype;
    if (messageWindow) wrapResult(messageWindow, 'commandName', 'rpg-msgCommandName');
    patchTextManager();
    if (root.PluginUtils) wrapArg0(root.PluginUtils, 'splitText', 'rpg-splitText');
    if (typeof root.insertLineBreaks === 'function') wrapArg0(root, 'insertLineBreaks', 'rpg-insertLineBreaks');
    var iavra = root.IAVRA;
    if (iavra && iavra.I18N) wrapArg0(iavra.I18N, 'localize', 'plugin-iavra');
    var dktools = root.DKTools;
    if (dktools && dktools.Localization) {
      wrapArg0(dktools.Localization, 'text', 'plugin-dktools');
      wrapArg0(dktools.Localization, 'localize', 'plugin-dktools');
      wrapArg0(dktools.Localization, 'convertText', 'plugin-dktools');
    }
    var srd = root.SRD;
    if (srd && srd.TranslationEngine) {
      wrapArg0(srd.TranslationEngine, 'translate', 'plugin-srd');
      wrapArg0(srd.TranslationEngine, 'getTranslation', 'plugin-srd');
    }
    patchPixiUpdateText();
  }

  // Translations are also written back into $dataXxx so menus, item names and
  // the database-derived windows change without a per-window hook. Originals
  // are kept so a reloaded map can be re-applied instead of compounding.
  var DATA_FIELDS = ['name', 'nickname', 'description', 'profile', 'message1', 'message2', 'message3', 'message4'];
  var dataSlots = null;

  function collectDataSlots() {
    var tables = ['$dataActors', '$dataClasses', '$dataSkills', '$dataItems', '$dataWeapons', '$dataArmors', '$dataEnemies', '$dataStates'];
    var slots = [];
    for (var t = 0; t < tables.length; t++) {
      var table = root[tables[t]];
      if (!table || typeof table !== 'object') continue;
      for (var i = 0; i < table.length; i++) {
        var record = table[i];
        if (!record || typeof record !== 'object') continue;
        for (var f = 0; f < DATA_FIELDS.length; f++) {
          var field = DATA_FIELDS[f];
          if (typeof record[field] !== 'string' || !record[field]) continue;
          slots.push({holder: record, field: field, original: record[field], site: 'writeback:' + tables[t]});
        }
      }
    }
    var system = root.$dataSystem;
    if (system && typeof system === 'object') {
      if (typeof system.gameTitle === 'string' && system.gameTitle) {
        slots.push({holder: system, field: 'gameTitle', original: system.gameTitle, site: 'writeback:$dataSystem'});
      }
      var groups = ['terms', 'words'];
      for (var g = 0; g < groups.length; g++) {
        var group = system[groups[g]];
        if (!group || typeof group !== 'object') continue;
        for (var key in group) {
          if (!hasOwn(group, key)) continue;
          var value = group[key];
          if (typeof value === 'string' && value) {
            slots.push({holder: group, field: key, original: value, site: 'writeback:' + groups[g] + '.' + key});
          } else if (Array.isArray(value)) {
            for (var j = 0; j < value.length; j++) {
              if (typeof value[j] !== 'string' || !value[j]) continue;
              slots.push({holder: value, field: String(j), original: value[j], site: 'writeback:' + groups[g] + '.' + key});
            }
          }
        }
      }
    }
    return slots;
  }

  function applyDataSlots() {
    if (!dataSlots) return 0;
    var changed = 0;
    for (var i = 0; i < dataSlots.length; i++) {
      var slot = dataSlots[i];
      var next = tr(slot.original, slot.site);
      if (slot.holder[slot.field] !== next) {
        slot.holder[slot.field] = next;
        changed++;
      }
    }
    if (changed) stats.writeback = (stats.writeback || 0) + changed;
    return changed;
  }

  function installDataWriteback() {
    if (root.__TRS_WRITEBACK__ === false) return 0;
    // $dataSystem / $dataActors are still undefined when the bootstrap runs at
    // document start, so an empty collection has to be retried. Caching [] here
    // would silently disable writeback for the whole session.
    if (!dataSlots || dataSlots.length === 0) {
      dataSlots = collectDataSlots();
      if (!dataSlots.length) return 0;
    }
    return applyDataSlots();
  }

  // A late install, or a writeback that changed the text source, leaves the
  // windows that are already on screen showing the old strings. Refreshing them
  // rebuilds command lists and redraws through the patched paths, which is what
  // makes the title/menu switch to the translation immediately.
  //
  // Detection is duck-typed on purpose: obfuscated or bundled MV builds rename
  // the constructors, and a name check (/^Window_/) then matches nothing at all.
  var refreshBurst = 0;
  var lastRefreshScene = null;
  var lastRefreshWindows = [];
  var lastMessageRefresh = 'not-run';
  var lastRpgMessageRender = null;
  var lastMessageLookupDiagnostics = null;

  function refreshSceneWindows() {
    var scene = root.SceneManager && root.SceneManager._scene;
    if (!scene) {
      lastRefreshWindows = [];
      return 0;
    }
    var count = 0;
    lastRefreshWindows = [];
    var visit = function (node, depth) {
      if (!node || depth > 6) return;
      var isWindow = typeof node.refresh === 'function'
        && node.contents
        && typeof node.contents.drawText === 'function';
      if (isWindow) {
        var className = node.constructor && node.constructor.name || 'Window(?)';
        var label = className + '.refresh/args=' + node.refresh.length;
        // Some plugins use refresh(text, position) for helper windows. Calling
        // those without arguments can clear or corrupt the active message.
        if (node.refresh.length > 0) {
          if (lastRefreshWindows.length < 8) lastRefreshWindows.push(label + ':args-required');
          return;
        }
        // Message name boxes and other attached helpers must be redrawn by
        // their parent message path, not as independent windows.
        if (node._parentWindow && typeof node._parentWindow.startMessage === 'function') {
          if (lastRefreshWindows.length < 8) lastRefreshWindows.push(label + ':message-child');
          return;
        }
        // Never restart a message that is currently being typed out.
        if (node._textState || typeof node.startMessage === 'function' || node._scrollText !== undefined) {
          if (lastRefreshWindows.length < 8) lastRefreshWindows.push(label + ':active-message');
          return;
        }
        try {
          node.refresh();
          count++;
          if (lastRefreshWindows.length < 8) lastRefreshWindows.push(label + ':refreshed');
        } catch (error) {
          if (lastRefreshWindows.length < 8) lastRefreshWindows.push(label + ':error:' + error.message);
        }
        return;
      }
      var kids = node.children;
      if (kids && kids.length) {
        for (var i = 0; i < kids.length; i++) visit(kids[i], depth + 1);
      }
    };
    visit(scene, 0);
    return count;
  }

  function patchGameObjects() {
    var ctorNames = ['Bitmap', 'Window_Base', 'Window_Message', 'Window_BattleLog'];
    // Window_Message.startMessage() takes no arguments. Treating it as a
    // string-taking draw method adds an unexpected undefined argument.
    var methods = ['drawText', 'drawTextEx', 'drawTextWithIcon', 'addText'];
    var i;
    var j;
    var ctor;
    for (i = 0; i < ctorNames.length; i++) {
      ctor = root[ctorNames[i]];
      if (!ctor || !ctor.prototype) continue;
      for (j = 0; j < methods.length; j++) wrapMethod(ctor.prototype, methods[j], [0]);
    }
    var pixiText = root.PIXI && root.PIXI.Text;
    var descriptor = pixiText && Object.getOwnPropertyDescriptor(pixiText.prototype, 'text');
    if (descriptor && descriptor.configurable && descriptor.set && !descriptor.set.__trsWrapped) {
      var setter = descriptor.set;
      var wrappedSetter = function (value) { return setter.call(this, tr(value)); };
      var wrappedDescriptor = {configurable: descriptor.configurable, enumerable: descriptor.enumerable};
      if (descriptor.get) wrappedDescriptor.get = descriptor.get;
      wrappedDescriptor.set = wrappedSetter;
      try {
        Object.defineProperty(wrappedSetter, '__trsWrapped', {value: true});
        Object.defineProperty(pixiText.prototype, 'text', wrappedDescriptor);
        stats.installed++;
      } catch (_) {}
    }
  }

  function patchRpgMakerMessages() {
    var proto = root.Game_Message && root.Game_Message.prototype;
    if (!proto || typeof proto.allText !== 'function' || proto.allText.__trsWrapped) return;
    var original = proto.allText;
    var wrapped = function () {
      var source = original.apply(this, arguments);
      var translated = translateMessageBlock(source, 'rpg-message');
      lastRpgMessageRender = {
        source: source,
        lookup: summarizeMessageLookup(source, map, lineToBlock)
      };
      return translated;
    };
    try {
      Object.defineProperty(wrapped, '__trsWrapped', {value: true});
      Object.defineProperty(wrapped, '__trsOriginal', {value: original});
      proto.allText = wrapped;
      patched.push(wrapped);
      stats.installed++;
    } catch (_) {}
  }

  function refreshCurrentRpgMessage(previousMap, previousLineIndex) {
    var message = root.$gameMessage;
    var proto = root.Game_Message && root.Game_Message.prototype;
    var wrappedAllText = proto && proto.allText;
    var originalAllText = wrappedAllText && wrappedAllText.__trsOriginal;
    var scene = root.SceneManager && root.SceneManager._scene;
    var messageWindow = scene && scene._messageWindow;
    var choiceWindow = messageWindow &&
      (messageWindow._choiceListWindow || messageWindow._choiceWindow);
    var choiceActive = !!(choiceWindow && (choiceWindow.active ||
      (typeof choiceWindow.isActive === 'function' && choiceWindow.isActive())));
    if (!message || !originalAllText || !messageWindow || typeof messageWindow.startMessage !== 'function') {
      lastMessageRefresh = 'missing-message-runtime';
      return false;
    }
    var busy = typeof message.isBusy === 'function' ? message.isBusy() : false;
    var originalText = originalAllText.call(message);
    var before = messageBlockWithMap(originalText, previousMap || {}, previousLineIndex || {});
    var after = messageBlockWithMap(originalText, map, lineToBlock);
    var queueLength = message._texts && message._texts.length;
    lastMessageRefresh = 'busy:' + busy + ',queue:' +
      (queueLength === undefined ? '?' : queueLength) + ',state:' +
      !!messageWindow._textState + ',changed:' + (before !== after) +
      ',chars:' + (originalText ? originalText.length : 0);
    var previousRender = lastRpgMessageRender;
    var previousLookup = summarizeMessageLookup(originalText, previousMap || {}, previousLineIndex || {});
    var currentLookup = summarizeMessageLookup(originalText, map, lineToBlock);
    lastMessageLookupDiagnostics = {
      activeSource: summarizeText(originalText),
      sameSourceAsLastRender: previousRender ? previousRender.source === originalText : null,
      sameMatchedKeysAsLastRender: previousRender
        ? sameMatchedKeys(previousRender.lookup, currentLookup)
        : null,
      newOutputSameAsLastRender: previousRender
        ? previousRender.lookup.outputText === after
        : null,
      oldAndNewOutputSame: before === after,
      lastRendered: previousRender ? {
        source: previousRender.lookup.source,
        output: previousRender.lookup.output,
        matches: previousRender.lookup.matches
      } : null,
      previousMap: {output: previousLookup.output, matches: previousLookup.matches},
      currentMap: {output: currentLookup.output, matches: currentLookup.matches}
    };
    if (!busy || before === after) return false;
    if (typeof messageWindow.isAnySubWindowActive === 'function' &&
        messageWindow.isAnySubWindowActive() && !choiceActive) {
      lastMessageRefresh += ',active-subwindow';
      return false;
    }

    var choiceIndex = choiceActive ? (typeof choiceWindow.index === 'function' ?
      choiceWindow.index() : choiceWindow._index) : -1;
    var choiceRestore = null;
    if (choiceActive) {
      if (typeof choiceWindow.start !== 'function' || typeof choiceWindow.deactivate !== 'function') {
        lastMessageRefresh += ',choice-window-unavailable';
        return false;
      }
      choiceRestore = choiceWindow.__trsChoiceRestoreState;
      if (choiceRestore && choiceWindow.start === choiceRestore.wrapper) {
        choiceRestore.index = choiceIndex;
      } else {
        choiceRestore = {
          index: choiceIndex,
          hadOwnStart: Object.prototype.hasOwnProperty.call(choiceWindow, 'start'),
          originalStart: choiceWindow.start,
          wrapper: null
        };
        choiceRestore.restore = function () {
          if (choiceWindow.__trsChoiceRestoreState !== choiceRestore) return;
          if (choiceWindow.start === choiceRestore.wrapper) {
            if (choiceRestore.hadOwnStart) choiceWindow.start = choiceRestore.originalStart;
            else delete choiceWindow.start;
          }
          delete choiceWindow.__trsChoiceRestoreState;
        };
        choiceRestore.wrapper = function () {
          var result;
          try {
            result = choiceRestore.originalStart.apply(this, arguments);
          } finally {
            if (choiceRestore.index >= 0 && typeof this.select === 'function') {
              this.select(choiceRestore.index);
            }
            if (typeof this.activate === 'function') this.activate();
            choiceRestore.restore();
          }
          return result;
        };
        choiceWindow.__trsChoiceRestoreState = choiceRestore;
        choiceWindow.start = choiceRestore.wrapper;
        root.setTimeout(function () {
          if (choiceWindow.__trsChoiceRestoreState !== choiceRestore) return;
          choiceRestore.restore();
          if (message._choices && message._choices.length && choiceWindow.visible !== false) {
            if (choiceRestore.index >= 0 && typeof choiceWindow.select === 'function') {
              choiceWindow.select(choiceRestore.index);
            }
            if (typeof choiceWindow.activate === 'function') choiceWindow.activate();
          }
        }, 10000);
      }
      choiceWindow.deactivate();
      lastMessageRefresh += ',choice-index:' + choiceIndex;
    }
    try {
      if ('pause' in messageWindow) messageWindow.pause = false;
      if (typeof messageWindow._waitCount === 'number') messageWindow._waitCount = 0;
      messageWindow.startMessage();
      if (messageWindow._textState) messageWindow._showFast = true;
      var refreshedState = messageWindow._textState;
      lastMessageRefresh += ',started,pause:' + !!messageWindow.pause +
        ',stateAfter:' + !!refreshedState;
      if (refreshedState) {
        lastMessageRefresh += ',textAfter:' +
          (refreshedState.text ? refreshedState.text.length : 0) +
          ',indexAfter:' + refreshedState.index;
      }
      return true;
    } catch (error) {
      if (choiceRestore && choiceRestore.restore) {
        choiceRestore.restore();
        if (choiceIndex >= 0 && typeof choiceWindow.select === 'function') {
          choiceWindow.select(choiceIndex);
        }
        if (typeof choiceWindow.activate === 'function') choiceWindow.activate();
      }
      lastMessageRefresh += ',error:' + error.message;
      return false;
    }
  }

  function beforeTranslationUpdate() {
    lastMessageLookupDiagnostics = null;
    // Several passes after a map change: the title screen may still be built
    // when the map lands, so one refresh is not always enough.
    if (refreshBurst < 8) refreshBurst = 8;
  }

  function translationUpdateDiagnostics(previousMap, previousLineIndex) {
    var refreshed = refreshCurrentRpgMessage(previousMap, previousLineIndex);
    var messageProto = root.Game_Message && root.Game_Message.prototype;
    var rpgMessageHook = !!(messageProto && messageProto.allText && messageProto.allText.__trsWrapped);
    return {
      rpgMessageHook: rpgMessageHook,
      currentMessageRefreshed: refreshed,
      messageRefresh: lastMessageRefresh,
      messageLookup: lastMessageLookupDiagnostics,
      hookSites: HOOK_SITES.length,
      writebackSlots: dataSlots ? dataSlots.length : 0,
      writebackApplied: stats.writeback || 0,
      refreshedWindows: stats.refreshed || 0,
      refreshedWindowSites: lastRefreshWindows.slice(0, 8)
    };
  }

  function describeEngineRuntime() {
    var engine = '';
    try { engine = (root.Utils && root.Utils.RPGMAKER_NAME) || ''; } catch (_) {}
    if (!engine) engine = root.Scene_Boot ? 'RPG Maker (unknown version)' : 'RPG Maker';
    var scene = root.SceneManager && root.SceneManager._scene;
    return {
      engine: engine,
      scene: (scene && scene.constructor && scene.constructor.name) || '',
      dataLoaded: !!root.$dataSystem,
      messageLoaded: !!root.Game_Message
    };
  }

  function engineStatus() {
    var scene = root.SceneManager && root.SceneManager._scene;
    var messageWindow = scene && scene._messageWindow;
    var message = root.$gameMessage;
    var textState = messageWindow && messageWindow._textState;
    var stateText = textState && typeof textState.text === 'string' ? textState.text : '';
    var choiceWindow = messageWindow &&
      (messageWindow._choiceListWindow || messageWindow._choiceWindow);
    var numberWindow = messageWindow && messageWindow._numberInputWindow;
    var itemWindow = messageWindow && messageWindow._eventItemWindow;
    return {
      writeback: stats.writeback || 0,
      writebackSlots: dataSlots ? dataSlots.length : 0,
      refreshed: stats.refreshed || 0,
      messageRefresh: lastMessageRefresh,
      refreshedWindowSites: lastRefreshWindows.slice(0, 8),
      messageLive: {
        focused: !!(root.document && root.document.hasFocus && root.document.hasFocus()),
        visible: root.document ? root.document.visibilityState : 'unknown',
        frame: root.Graphics ? root.Graphics.frameCount : -1,
        sceneStarted: !!(scene && scene.isStarted && scene.isStarted()),
        busy: !!(message && message.isBusy && message.isBusy()),
        queued: message && message._texts ? message._texts.length : -1,
        state: !!textState,
        index: textState ? textState.index : -1,
        textLength: stateText.length,
        textHead: stateText.slice(0, 24),
        open: !!(messageWindow && messageWindow.isOpen && messageWindow.isOpen()),
        openness: messageWindow ? messageWindow.openness : -1,
        wait: messageWindow ? messageWindow._waitCount : -1,
        pause: !!(messageWindow && messageWindow.pause),
        choiceActive: !!(choiceWindow && (choiceWindow.active ||
          (typeof choiceWindow.isActive === 'function' && choiceWindow.isActive()))),
        numberActive: !!(numberWindow && numberWindow.active),
        itemActive: !!(itemWindow && itemWindow.active)
      }
    };
  }

  function installSelectedEngine() {
    patchGameObjects();
    patchRpgMakerDeep();
    patchRpgMakerMessages();
    var hooksBefore = stats.installed;
    var writebackChanged = installDataWriteback();
    nameSpriteTick++;
    if (nameSpriteTick <= 8 || nameSpriteTick % 8 === 0) patchNameSprites();
    var scene = root.SceneManager && root.SceneManager._scene;
    var sceneChanged = !!scene && scene !== lastRefreshScene;
    if (sceneChanged) {
      lastRefreshScene = scene;
      if (refreshBurst < 2) refreshBurst = 2;
    }
    if (writebackChanged || stats.installed !== hooksBefore || sceneChanged || refreshBurst > 0) {
      stats.refreshed = refreshSceneWindows();
      if (refreshBurst > 0) refreshBurst--;
    }
  }

  install();
