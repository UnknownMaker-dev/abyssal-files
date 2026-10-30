// Abyssal Files ⇄ Ace glue. The app talks to the page through window.abyssal (evaluateJavascript) and the page
// talks back through window.AbyssalBridge (an Android @JavascriptInterface). Autocomplete and find & replace stay in
// the app; Ace only edits and draws the text.
(function () {
  "use strict";
  var B = window.AbyssalBridge;
  ace.config.set("basePath", "./");
  ace.config.set("modePath", "./");
  ace.define("ace/theme/abyssal", ["require", "exports"], function (require, exports) {
    exports.isDark = true;
    exports.cssClass = "ace-abyssal";
    exports.cssText = "";
  });

  // The WebView can't read the system clipboard by itself: Ace's Copy/Paste menu goes through the app
  try {
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: {
        readText: function () { return Promise.resolve(B.clipboardGet()); },
        writeText: function (t) { B.clipboardSet(t); return Promise.resolve(); }
      }
    });
  } catch (e) {}

  var Range = ace.require("ace/range").Range;
  var editor, session, lastUndo = "", clean = true, lastCaret = "";

  function idx(p) { return session.doc.positionToIndex(p, 0); }
  function pos(i) { return session.doc.indexToPosition(i, 0); }

  function reportUndo() {
    var um = session.getUndoManager();
    var state = (um.hasUndo() ? "1" : "0") + (um.hasRedo() ? "1" : "0");
    if (state !== lastUndo) { lastUndo = state; B.undoState(um.hasUndo(), um.hasRedo()); }
    var nowClean = um.isClean();
    if (nowClean !== clean) { clean = nowClean; B.dirty(!nowClean); }
  }

  function reportCursor() {
    var c = editor.getCursorPosition();
    B.cursor(c.row, c.column, idx(c), editor.selection.isEmpty());
  }

  // Where the caret is on screen (physical pixels), so the app can place the suggestion popup
  function reportCaret() {
    var c = editor.getCursorPosition();
    var p = editor.renderer.textToScreenCoordinates(c.row, c.column);
    var r = editor.container.getBoundingClientRect(), k = window.devicePixelRatio || 1;
    var x = Math.round((p.pageX - r.left) * k), top = Math.round((p.pageY - r.top) * k);
    var bottom = Math.round((p.pageY - r.top + editor.renderer.lineHeight) * k);
    var s = x + "," + top + "," + bottom;
    if (s !== lastCaret) { lastCaret = s; B.caret(x, top, bottom); }
  }

  function search(q) { return { needle: q, caseSensitive: false, regExp: false, wrap: true }; }

  function counter(q) {
    var Search = ace.require("ace/search").Search;
    var all = new Search().set(search(q)).findAll(session), cur = editor.getSelectionRange(), n = 0;
    for (var i = 0; i < all.length; i++) if (all[i].isEqual(cur)) n = i + 1;
    return n + "/" + all.length;
  }

  window.abyssal = {
    init: function (json) {
      var o = JSON.parse(json);
      editor = ace.edit("editor", {
        useWorker: false,
        showPrintMargin: false,
        animatedScroll: false,
        scrollPastEnd: 0.5,
        fadeFoldWidgets: false,
        showFoldWidgets: true,
        displayIndentGuides: true,
        highlightActiveLine: true,
        tabSize: o.indent || 2,
        useSoftTabs: true,
        newLineMode: "unix",
        wrap: !!o.wrap,
        fontSize: o.fontSize + "px",
        fontFamily: "monospace",
        enableMobileMenu: true
      });
      editor.setTheme("ace/theme/abyssal");
      editor.renderer.setScrollMargin(6, 6, 0, 0);
      editor.commands.addCommand({ name: "find", bindKey: { win: "Ctrl-F", mac: "Command-F" }, exec: function () { B.find(); } });
      editor.commands.removeCommand("replace");
      session = editor.session;
      session.setMode(ace.require("ace/ext/modelist").getModeForPath(o.name || "").mode);
      session.setValue(B.text());
      session.getUndoManager().markClean();
      session.on("change", function (d) {
        B.delta(d.action === "insert", idx(d.start), d.lines.join("\n"));
        setTimeout(reportUndo, 0);
      });
      editor.selection.on("changeCursor", reportCursor);
      editor.selection.on("changeSelection", reportCursor);
      editor.renderer.on("afterRender", reportCaret);
      window.addEventListener("resize", function () { editor.resize(); editor.renderer.scrollCursorIntoView(null, 0.5); });
      if (o.index > 0) { editor.moveCursorToPosition(pos(o.index)); editor.renderer.scrollCursorIntoView(null, 0.5); }
      editor.focus();
      reportCursor();
      B.ready();
    },
    theme: function (css) {
      document.getElementById("abyssal-theme").textContent = css;
      if (editor) editor.renderer.updateFull(true);
    },
    sendText: function (id) { B.got(id, editor.getValue()); },
    markClean: function () { session.getUndoManager().markClean(); reportUndo(); },
    insert: function (s) { editor.insert(s); editor.focus(); },
    // Replaces [start, end) and puts the caret at start + caret (used by autocomplete)
    replace: function (start, end, s, caret) {
      var a = pos(start), b = pos(end);
      session.replace(new Range(a.row, a.column, b.row, b.column), s);
      editor.clearSelection();
      editor.moveCursorToPosition(pos(start + caret));
      editor.focus();
    },
    tab: function () { editor.indent(); editor.focus(); },
    move: function (d) { editor.clearSelection(); if (d < 0) editor.navigateLeft(1); else editor.navigateRight(1); editor.focus(); },
    undo: function () { editor.undo(); },
    redo: function () { editor.redo(); },
    fontSize: function (n) { editor.setFontSize(n + "px"); },
    wrap: function (b) { session.setUseWrapMode(!!b); },
    focus: function () { editor.focus(); },
    blur: function () { editor.blur(); },
    // Find & replace, each returns "current/total"
    find: function (q, backwards, skipCurrent) {
      if (!q) { editor.clearSelection(); return "0/0"; }
      var o = search(q);
      o.backwards = !!backwards;
      o.skipCurrent = !!skipCurrent;
      if (!skipCurrent) o.start = editor.getSelectionRange().start;
      editor.find(q, o, false);
      editor.renderer.scrollSelectionIntoView(editor.selection.anchor, editor.selection.lead, 0.5);
      return counter(q);
    },
    replace1: function (q, r) {
      if (!q) return "0/0";
      var range = editor.getSelectionRange();
      if (session.getTextRange(range).toLowerCase() === q.toLowerCase()) session.replace(range, r);
      return abyssal.find(q, false, false);
    },
    replaceAll: function (q, r) {
      if (!q) return 0;
      editor.$search.set(search(q));
      return editor.replaceAll(r, search(q));
    }
  };
  B.loaded();
})();
