/*
 * 侧栏导航：一份清单，19 个页面共用。改专题顺序/标题只改这里。
 * 前半讲「系统怎么跑」，后半讲「代码怎么写」；15 之前插一条分组标题。
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
    ['07-sql-sandbox.html', '07', 'SQL 工作台与沙箱'],
    ['08-frontend.html', '08', '前端与流式渲染'],
    ['09-observability.html', '09', '可观测与发版'],
    ['10-aiops.html', '10', 'AIOps 自诊断'],
    ['11-trust-boundary.html', '11', '信任边界'],
    ['12-resume.html', '12', '简历与求职'],
    ['13-interactive-content.html', '13', '交互式内容'],
    ['14-mcp-tools.html', '14', 'MCP 工具面'],
    ['15-code-conventions.html', '15', '代码规范'],
    ['16-testing.html', '16', '测试规范'],
    ['17-commit-review-release.html', '17', '提交、评审与发布'],
    ['18-docs-and-decisions.html', '18', '文档与决策']
  ];
  var GROUP_AT = '15';

  var here = decodeURIComponent((location.pathname.split('/').pop() || 'index.html'));
  var items = TOPICS.map(function (t) {
    var head = t[1] === GROUP_AT ? '<span class="nav-group">工程规范</span>' : '';
    var on = t[0] === here ? ' class="on"' : '';
    return head + '<a href="' + t[0] + '"' + on + '><span class="num">' + t[1] + '</span><span>' + t[2] + '</span></a>';
  }).join('');

  document.body.insertAdjacentHTML('afterbegin',
    '<aside class="sidebar">' +
      '<a class="brand" href="index.html">Paideia 架构导读<small>19 页 · 离线可读</small></a>' +
      '<nav>' + items + '</nav>' +
    '</aside>');
})();
