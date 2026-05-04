import axios from 'axios'

// 查询全部知识库列表。
export async function ragListKnowledgeBases() {
  const res = await axios.get('/api/rag/kb/list')
  return res.data
}

// 创建新知识库。
export async function ragCreateKnowledgeBase(knowledgeBaseName) {
  const res = await axios.post('/api/rag/kb/create', {
    knowledge_base_name: knowledgeBaseName,
  })
  return res.data
}

// 删除空知识库（有文件时后端会拦截）。
export async function ragDeleteKnowledgeBase({ knowledgeBaseName }) {
  const res = await axios.post('/api/rag/kb/delete', {
    knowledge_base_name: knowledgeBaseName,
  })
  return res.data
}

// 获取指定知识库的文件列表。
export async function ragListFiles(knowledgeBaseName) {
  const res = await axios.get('/api/rag/kb/files', {
    params: { knowledge_base_name: knowledgeBaseName },
  })
  return res.data
}

// 删除知识库中的单个文件。
export async function ragDeleteFile({ knowledgeBaseName, fileName }) {
  const res = await axios.post('/api/rag/file/delete', {
    knowledge_base_name: knowledgeBaseName,
    file_name: fileName,
  })
  return res.data
}

// 上传文件到指定知识库（multipart/form-data）。
export async function ragUpload({ knowledgeBaseName, files }) {
  const formData = new FormData()
  formData.append('knowledge_base_name', knowledgeBaseName)
  files.forEach((file) => formData.append('files', file))
  const res = await axios.post('/api/rag/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return res.data
}

// 在指定知识库范围内发起 RAG 问答。
export async function ragChat({ knowledgeBaseName, question }) {
  const res = await axios.post('/api/rag/chat', {
    knowledge_base_name: knowledgeBaseName,
    question,
  })
  return res.data
}
