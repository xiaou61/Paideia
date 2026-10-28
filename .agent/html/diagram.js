/* mermaid 初始化：主题跟着系统深浅色走，字号与正文一致。所有页面共用。 */
(function () {
  if (!window.mermaid) return;
  var dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  window.mermaid.initialize({
    startOnLoad: false,
    theme: dark ? 'dark' : 'neutral',
    fontFamily: 'system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif',
    fontSize: 14,
    // 不按容器宽度缩放：宽图宁可横向滚动，也不要缩到看不清。.diagram 容器本来就 overflow-x:auto。
    // wrappingWidth 默认 200px，中文五六个字就折行、图会挤成一条窄柱，所以放宽。
    flowchart: { curve: 'basis', htmlLabels: true, padding: 12, useMaxWidth: false, wrappingWidth: 340 },
    sequence: { actorMargin: 30, width: 118, useMaxWidth: false },
    state: { useMaxWidth: false },
    themeVariables: { lineColor: dark ? '#6f6f7a' : '#b9b9b0' }
  });

  /* 比容器宽的图在 figcaption 前面标一句「可横向滚动」——否则读者不知道右边还有内容。 */
  function markWideDiagrams() {
    document.querySelectorAll('.diagram').forEach(function (box) {
      if (box.scrollWidth > box.clientWidth + 10) {
        var figure = box.closest('figure');
        if (figure) figure.classList.add('scrollable');
      }
    });
  }

  window.mermaid.run({ querySelector: 'pre.mermaid' })
    .then(markWideDiagrams)
    .catch(function (error) { console.error('mermaid 渲染失败：', error); });
})();
