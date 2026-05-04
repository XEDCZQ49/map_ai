<script setup>

import DRWChat from './SubMenu/DRWChat/DRWChat.vue'
import EnvAnalysis from './SubMenu/EnvAnaly/EnvAnalysis.vue'
import LLMConfig from './SubMenu/KB/LLMConfig.vue'
import RAGChat from './SubMenu/RAGChat/RAGChat.vue'
import File from './SubMenu/Fileprocess/File.vue'
import DocGen from './SubMenu/DocumentGen/DocGen.vue'

</script>

<template>
    <div class="experience">
      <div class="experience_wrapper">
        <div class="experience_content" >
          <el-tabs
            class="experience_tabs"
            type="border-card"
            :lazy="false" >
            <el-tab-pane label="环境分析" key="1">
            <EnvAnalysis></EnvAnalysis>
            </el-tab-pane>
            <el-tab-pane label="要图标绘" key="2">
              <DRWChat></DRWChat>
            </el-tab-pane>
            <el-tab-pane label="一键部署" key="6">
              <File></File>
            </el-tab-pane>
            <el-tab-pane label="文书拟制" key="3">
              <DocGen></DocGen>
            </el-tab-pane>
            <el-tab-pane label="情报查询" key="5">
              <RAGChat></RAGChat>
            </el-tab-pane>
            <el-tab-pane label="智能配置" key="8">
              <LLMConfig></LLMConfig>
            </el-tab-pane>
            
            
            
          </el-tabs>
        </div>

      </div>
    </div>

</template>


<style lang="less">
@import "./style.less";

</style>
