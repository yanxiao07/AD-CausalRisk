/**
 * 构建入口：先为本进程指定项目内的临时目录，再调用 Vite。
 *
 * 背景：Windows 上 esbuild 会把大体积中间产物写进 %TEMP%，个别机器（杀软/组策略
 * 占用）在删除这些临时文件时会返回 Access is denied，导致 `vite build` 随机失败。
 * 把 TEMP/TMP 指到项目内 .tmp 后可稳定复现成功，CI（Linux）不受影响。
 */
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const tempDir = join(projectRoot, '.tmp')

mkdirSync(tempDir, { recursive: true })
process.env.TEMP = tempDir
process.env.TMP = tempDir

const { build } = await import('vite')
await build({ root: projectRoot })
