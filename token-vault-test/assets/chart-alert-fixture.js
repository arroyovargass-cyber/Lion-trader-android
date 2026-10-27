/* Test APK only. Injected into the production WebView with device networking disabled. */
(() => {
  'use strict';
  const LABEL = 'DATOS DE PRUEBA · NO SON SEÑALES REALES';
  const TOKEN = 'native-chart-fixture-token-no-real-access';
  const requests = [];
  let rejected = 0, expired = false, newEvent = false, holdOther = false, releaseOther;
  const $ = id => document.getElementById(id);
  const check = (value, stage) => { if (!value) throw Error(stage); };
  const iso = offset => new Date(Date.now() + offset).toISOString();
  const waitFor = async predicate => {
    for (let i = 0; i < 160; i++) {
      if (predicate()) return;
      await new Promise(resolve => setTimeout(resolve, 25));
    }
    throw Error('fixture-wait-timeout');
  };
  const signal = (market, watch = false) => ({
    id: market + '-VELAS', market, analysis_mode: 'VELAS', strategy_origin: 'VELAS',
    direction: 'COMPRA', score: watch ? 85 : 94, state: watch ? 'OBSERVACION' : 'OPERABLE',
    confirmed: !watch, preconfirmed: watch, operable: !watch,
    confirmation_event_id: newEvent ? 2 : 1, confirmed_at: iso(-60000),
    updated_at: iso(expired ? -3600000 : -30000), data_valid_until: iso(expired ? -1000 : 300000),
    entry: market === 'EURUSD' ? 1.1 : 1.3,
    stop_loss: market === 'EURUSD' ? 1.09 : 1.29, tp2: market === 'EURUSD' ? 1.12 : 1.32,
    entry_schedule: {label: LABEL},
    manual_block_reason: watch ? 'En observación; ejecución bloqueada.' : '',
    actions: {COMPRA: {demo_enabled: !watch, real_ticket_enabled: false}, VENTA: {demo_enabled: false, real_ticket_enabled: false}}
  });
  const event = id => ({
    id, signal_id: 'EURUSD-VELAS', market: 'EURUSD', strategy_origin: 'VELAS', direction: 'COMPRA',
    confirmation_score: 94, entry: 1.1, stop_loss: 1.09, tp2: 1.12,
    current: id === (newEvent ? 2 : 1), execution_allowed: true, created_at: iso(-60000), expires_at: iso(300000)
  });
  // Never delegate to the original fetch. Unknown paths/methods fail closed, including all financial POSTs.
  window.fetch = async (input, options = {}) => {
    const url = new URL(input, location.href), method = options.method || 'GET';
    const allowed = ['/api/v1/manual-entry-options', '/api/v1/notifications', '/api/v1/chart', '/api/v1/real-auto/status'];
    if (method !== 'GET' || url.origin !== location.origin || !allowed.includes(url.pathname)) {
      rejected++; throw Error('fixture-request-rejected');
    }
    requests.push({path: url.pathname, query: url.search, method});
    let data;
    if (url.pathname.endsWith('/manual-entry-options')) {
      data = {catalog: [{market: 'EURUSD'}, {market: 'GBPUSD'}], items: [signal('EURUSD'), signal('GBPUSD', true)],
        signal_max_age_seconds: 600, server_time: iso(0), broker_demo: {orders_enabled: true, automatic_enabled: false},
        broker_real: {connection_verified: false, manual_ticket_ready: false, block_reasons: ['REAL_DAILY_LOSS_UNVERIFIED']}};
    } else if (url.pathname.endsWith('/notifications')) {
      const after = Number(url.searchParams.get('after_id') || 0), events = newEvent ? [event(1), event(2)] : [event(1)];
      data = {items: url.searchParams.get('recent') === 'true' ? events : events.filter(e => e.id > after), cursor: newEvent ? 2 : 1, has_more: false};
    } else if (url.pathname.endsWith('/chart')) {
      const market = url.searchParams.get('market'), frame = url.searchParams.get('timeframe');
      check(['EURUSD', 'GBPUSD'].includes(market) && ['M5', 'M15', 'H1', 'H4'].includes(frame), 'chart-query');
      if (holdOther && market === 'GBPUSD') await new Promise(resolve => { releaseOther = resolve; });
      const price = market === 'EURUSD' ? 1.1 : 1.3, old = expired ? -86400000 : 0;
      data = {status: 'ok', market, timeframe: frame, candles: [
        {time: iso(old - 300000), open: price, high: price + .006, low: price - .003, close: price + .003},
        {time: iso(old - 1000), open: price + .003, high: price + .007, low: price, close: price + .004}
      ]};
    } else {
      data = {status: 'ok', supported: true, armed: false, campaign: null, block_reasons: ['REAL_DAILY_LOSS_UNVERIFIED']};
    }
    const snapshot = JSON.parse(JSON.stringify(data));
    return {ok: true, status: 200, json: async () => snapshot};
  };
  const badge = document.createElement('div');badge.id = 'nativeFixtureLabel';badge.textContent = LABEL;
  Object.assign(badge.style, {position: 'fixed', top: '0', left: '0', right: '0', zIndex: '99999', background: '#ffdf75', color: '#111', fontSize: '12px', fontWeight: 'bold', padding: '5px', textAlign: 'center'});
  document.body.append(badge);
  const assertNoOrders = () => check(rejected === 0 && requests.every(r => r.method === 'GET'), 'unexpected-request');
  const actions = () => [...$('detailContent').querySelectorAll('button')];
  const button = text => actions().find(b => b.textContent === text);
  const chartLoaded = () => $('chartPlot').querySelector('svg') !== null;
  const chartQuery = () => requests.filter(r => r.path === '/api/v1/chart').at(-1);
  const assertQuery = (market, frame) => {
    const query = new URLSearchParams(chartQuery().query);
    check(query.get('market') === market && query.get('timeframe') === frame && query.get('count') === '60', 'chart-market-timeframe');
  };
  window.__lionChartAlertFixture = {
    async prepare() {
      await accessBootstrap;
      await startSession(TOKEN, false);
      clearInterval(timer);clearInterval(clockTimer);
      check(healthy && $('visualNotice').hidden, 'initial-notification-suppressed');
      check($('connectionSummary').textContent.includes('1 confirmadas existentes'), 'initial-existing-summary');
      $('homeTop').querySelector('button').click();
      await waitFor(chartLoaded);
      check($('chartM5').getAttribute('aria-pressed') === 'true', 'default-m5');assertQuery('EURUSD', 'M5');
      check($('chartPlot').querySelectorAll('rect').length === 2, 'native-svg-candlesticks');
      for (const text of ['Entrada 1.1', 'SL 1.09', 'TP2 1.12']) check($('chartPlot').textContent.includes(text), 'plan-levels');
      check(!button('Compra demo').disabled && button('Venta demo').disabled, 'matching-direction-only');
      $('chartPanel').scrollIntoView({block: 'center'});
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      const rect = $('chartPlot').querySelector('svg').getBoundingClientRect();
      check(rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.top < innerHeight, 'svg-visible-in-native-viewport');
      check(getComputedStyle($('nativeFixtureLabel')).position === 'fixed', 'visible-test-label');
      assertNoOrders();
    },
    async framesAndExpiry() {
      for (const frame of ['M15', 'H1', 'H4']) {
        await $('chart' + frame).onclick();check(chartLoaded(), 'timeframe-svg');assertQuery('EURUSD', frame);
        check($('chart' + frame).getAttribute('aria-pressed') === 'true', 'timeframe-selected');
      }
      const before = requests.length;await $('chartRefresh').onclick();
      check(requests.length === before + 1, 'explicit-chart-refresh');assertQuery('EURUSD', 'H4');
      $('tabAlerts').click();
      check($('currentConfirmed').textContent.includes('EURUSD') && $('currentWatch').textContent.includes('ejecución bloqueada'), 'current-readings');
      holdOther = true;$('currentWatch').querySelector('button').click();
      check(!chartLoaded(), 'previous-entry-chart-cleared');
      await waitFor(() => Boolean(releaseOther));holdOther = false;releaseOther();
      await waitFor(chartLoaded);assertQuery('GBPUSD', 'M5');
      check($('chartSummary').textContent.includes('GBPUSD') && $('chartPlot').textContent.includes('Entrada 1.3'), 'other-entry-chart');
      check(actions().every(b => b.disabled), 'watch-actions-blocked');
      $('tabAlerts').click();$('currentConfirmed').querySelector('button').click();await waitFor(chartLoaded);
      expired = true;await refresh();
      await waitFor(() => $('chartSummary').textContent.includes('Datos antiguos'));
      check(actions().every(b => b.disabled) && $('detailContent').textContent.includes('Datos caducados'), 'expired-actions-blocked');
      check(!chartLoaded(), 'stale-candles-not-rendered');
      assertNoOrders();
    },
    async alert() {
      expired = false;$('tabAlerts').click();newEvent = true;await refresh();
      check(!$('visualNotice').hidden, 'new-correlated-alert');
      check($('visualNoticeText').textContent.includes('EURUSD') && $('visualNoticeText').textContent.includes('94/100'), 'alert-content');
      const rect = $('visualNotice').getBoundingClientRect();
      check(getComputedStyle($('visualNotice')).position === 'fixed' && rect.width > 0 && rect.height > 0 && rect.bottom <= innerHeight && rect.top >= 0, 'fixed-banner-in-viewport');
      check($('alerts').textContent.includes('reemplazada') && $('alerts').textContent.includes('operable DEMO'), 'event-history');
      check($('currentConfirmed').textContent.includes('EURUSD') && $('currentWatch').textContent.includes('ejecución bloqueada'), 'visible-current-readings');
      window.scrollTo(0, 0);assertNoOrders();
    },
    async dismiss() {
      const before = requests.length;$('closeNotice').click();
      check($('visualNotice').hidden && requests.length === before, 'dismiss-without-financial-request');
      assertNoOrders();disconnectSession();
    }
  };
})();
