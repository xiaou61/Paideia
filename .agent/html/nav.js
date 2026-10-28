/*
 * 侧栏导航：一份清单，11 个页面共用。改专题顺序/标题只改这里。
 * 用 document.currentScript 而不是 location，file:// 下取文件名更可靠。
 */
(function () {
  var TOPICS = [
    ['index.html', '00', '总览与读法'],
    ['01-overview.html', '01', '全景：它由什么拼成'],
    ['02-request-lifecycle.html', '02', '一次请求的一生'],
    ['03-ai-conversation.html', '03', 'AI 对话与积分'],
    ['04-memory.html', '04', '记忆：写与读'],
    ['05-learning-loop.html', '05', '学习闭环与掌握度'],
    ['06-content-pipeline.html', '06', '内容生产线'],
    ['07-data-model.html', '07', '数据模型：59 张表'],
    ['08-frontend.html', '08', '前端与流式渲染'],
    ['09-observability.html', '09', '可观测与发版'],
    ['10-trust-boundary.html', '10', '信任边界']
  ];

  var here = decodeURIComponent((location.pathname.split('/').pop() || 'index.html'));
  var items = TOPICS.map(function (t) {
    var on = t[0] === here ? ' class="on"' : '';
    return '<a href="' + t[0] + '"' + on + '><span class="num">' + t[1] + '</span><span>' + t[2] + '</span></a>';
  }).join('');

  document.body.insertAdjacentHTML('afterbegin',
    '<aside class="sidebar">' +
      '<a class="brand" href="index.html">Paideia 架构导读<small>11 个专题 · 离线可读</small></a>' +
      '<nav>' + items + '</nav>' +
    '</aside>');
})();
