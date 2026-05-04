import axios from 'axios'

// 获取当前可配置的 Prompt 节点列表。
export async function promptNodes() {
  const res = await axios.get('/api/prompt/map_annotation/nodes')
  return res.data
}

// 获取指定节点 Prompt，type 可选 now/def。
export async function promptGet(node, type = 'now') {
  const res = await axios.get('/api/prompt/map_annotation/get', { params: { node, type } })
  return res.data
}

// 保存指定节点的 now Prompt（前端热更新入口）。
export async function promptSaveNow(node, prompt) {
  const res = await axios.post('/api/prompt/map_annotation/save', { node, prompt })
  return res.data
}
