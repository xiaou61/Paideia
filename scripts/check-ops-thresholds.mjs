#!/usr/bin/env node
// 阈值单一来源的仓库内检查（WORK-049）。
//
// 安装期还有第二道检查（渲染后仍有占位符就退出）。这里挡的是更早的一类错：
// 有人把 conf 的值改到范围外，或者把规则文件里的占位符改回硬编码——那会让
// 「改一处、两侧一致」的约定悄悄失效，而线上要等下一次告警才会发现。
//
// 用法：node scripts/check-ops-thresholds.mjs

import { readFileSync } from 'node:fs'

const root = new URL('..', import.meta.url)
const confPath = new URL('deploy/ops/ops-thresholds.conf', root)
const rulesPath = new URL('deploy/ops/provisioning/alerting/paideia-rules.yaml', root)

const errors = []
const values = {}

for (const raw of readFileSync(confPath, 'utf8').split(/\r?\n/)) {
  const line = raw.trim()
  if (line === '' || line.startsWith('#')) {
    continue
  }
  const match = /^([A-Z][A-Z0-9_]*)=(.+)$/.exec(line)
  if (match === null) {
    errors.push(`无法解析：${line}`)
    continue
  }
  const value = Number(match[2])
  if (!Number.isFinite(value)) {
    errors.push(`${match[1]} 不是数字：${match[2]}`)
    continue
  }
  values[match[1]] = value
}

const ratioKeys = ['DISK_USED_ERROR', 'UNIT_MEMORY_ERROR']
for (const key of ratioKeys) {
  if (!(key in values)) {
    errors.push(`缺少 ${key}`)
  } else if (values[key] <= 0 || values[key] >= 1) {
    errors.push(`${key} 必须在 0 与 1 之间（当前 ${values[key]}）`)
  }
}

if (!('BACKUP_STALE_HOURS' in values)) {
  errors.push('缺少 BACKUP_STALE_HOURS')
} else if (!Number.isInteger(values.BACKUP_STALE_HOURS) || values.BACKUP_STALE_HOURS <= 0) {
  errors.push(`BACKUP_STALE_HOURS 必须是正整数（当前 ${values.BACKUP_STALE_HOURS}）`)
}

const rules = readFileSync(rulesPath, 'utf8')
const placeholders = [
  '__DISK_FREE_RATIO__',
  '__DISK_FREE_PERCENT__',
  '__UNIT_MEMORY_RATIO__',
  '__UNIT_MEMORY_PERCENT__',
  '__BACKUP_STALE_HOURS__',
]
for (const placeholder of placeholders) {
  if (!rules.includes(placeholder)) {
    errors.push(`paideia-rules.yaml 缺少占位符 ${placeholder}（可能被改回硬编码）`)
  }
}

if (errors.length > 0) {
  for (const error of errors) {
    console.error(`✗ ${error}`)
  }
  process.exit(1)
}

console.log(
  `✓ 阈值一致：磁盘已用 ${values.DISK_USED_ERROR}、单元内存 ${values.UNIT_MEMORY_ERROR}、备份 ${values.BACKUP_STALE_HOURS}h`,
)
