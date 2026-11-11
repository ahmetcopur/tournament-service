// Load test: a mix of level ups (writes: Postgres transaction + Redis script) and leaderboard reads.
//
//   k6 run -e BASE_URL=http://localhost:8080 load-test/k6.js
//
// Knobs: PLAYERS (default 250), RATE in requests/second (default 400), DURATION (default 60s).
// The tournament must be open; after 20:00 UTC start the app with TOURNAMENT_CLOSES_AT=24h.
import http from 'k6/http';
import { check, fail } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PLAYERS = Number(__ENV.PLAYERS || 250);
const RATE = Number(__ENV.RATE || 400);
const DURATION = __ENV.DURATION || '60s';

const ENDPOINTS = ['level_up', 'group_leaderboard', 'rank', 'country_leaderboard'];

export const options = {
  setupTimeout: '300s',
  scenarios: {
    mixed: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 400,
    },
  },
  // One threshold per endpoint, so the summary reports each endpoint on its own.
  thresholds: Object.assign(
    { http_req_failed: ['rate<0.01'] },
    ...ENDPOINTS.map((e) => ({ [`http_req_duration{endpoint:${e}}`]: ['p(99)<500'] })),
  ),
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };
const pick = (list) => list[Math.floor(Math.random() * list.length)];

// Creates players, levels each one to 20, and joins them all, so level ups below score points.
export function setup() {
  const t = http.get(`${BASE}/tournaments/current`).json();
  if (!t.open) {
    fail(`tournament ${t.id} is not open (${t.startsAt} to ${t.endsAt}); start the app with TOURNAMENT_CLOSES_AT=24h`);
  }
  const run = Date.now().toString(36);
  const created = http.batch(Array.from({ length: PLAYERS }, (_, i) =>
    ['POST', `${BASE}/users`, JSON.stringify({ username: `k6_${run}_${i}` }), JSON_HEADERS]));
  const ids = created.map((r) => r.json('id'));
  for (let level = 1; level < 20; level++) {
    http.batch(ids.map((id) => ['POST', `${BASE}/users/${id}/levels`, null]));
  }
  const joins = http.batch(ids.map((id) =>
    ['POST', `${BASE}/tournaments/current/entries`, JSON.stringify({ userId: id }), JSON_HEADERS]));
  const joined = joins.filter((r) => r.status === 201);
  if (joined.length !== ids.length) {
    fail(`only ${joined.length} of ${ids.length} joins succeeded: ${joins.find((r) => r.status !== 201).body}`);
  }
  const groups = [...new Set(joined.map((r) => r.json('groupId')))];
  const active = joined.filter((r) => r.json('status') === 'ACTIVE').length;
  console.log(`setup: ${ids.length} players in ${groups.length} groups (${active} groups full)`);
  return { tournamentId: t.id, ids, groups };
}

// 60% level ups, 30% group boards, 5% rank lookups, 5% country board.
export default function (data) {
  const roll = Math.random();
  let res;
  if (roll < 0.6) {
    res = http.post(`${BASE}/users/${pick(data.ids)}/levels`, null, { tags: { endpoint: 'level_up' } });
  } else if (roll < 0.9) {
    res = http.get(`${BASE}/groups/${pick(data.groups)}/leaderboard`, { tags: { endpoint: 'group_leaderboard' } });
  } else if (roll < 0.95) {
    res = http.get(`${BASE}/tournaments/${data.tournamentId}/users/${pick(data.ids)}/rank`,
      { tags: { endpoint: 'rank' } });
  } else {
    res = http.get(`${BASE}/tournaments/${data.tournamentId}/countries/leaderboard`,
      { tags: { endpoint: 'country_leaderboard' } });
  }
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const ms = (v) => `${v.toFixed(1)} ms`;
  const lines = [
    '| Endpoint | p50 | p90 | p99 | max |',
    '|---|---|---|---|---|',
    ...ENDPOINTS.map((e) => {
      const v = data.metrics[`http_req_duration{endpoint:${e}}`].values;
      return `| ${e} | ${ms(v.med)} | ${ms(v['p(90)'])} | ${ms(v['p(99)'])} | ${ms(v.max)} |`;
    }),
  ];
  const reqs = data.metrics.http_reqs.values;
  const failed = data.metrics.http_req_failed.values.rate;
  lines.push('', `${reqs.count} requests (${reqs.rate.toFixed(0)}/s incl. setup), ${(failed * 100).toFixed(2)}% failed`);
  const text = lines.join('\n') + '\n';
  return { stdout: text, 'load-test/results/summary.md': text, 'load-test/results/summary.json': JSON.stringify(data, null, 2) };
}
