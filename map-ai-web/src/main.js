import { createApp } from 'vue'
import App from './App.vue'
import axios from 'axios'
import store from './store'

import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'

import './assets/Cesium/Widgets/widgets.css'

const app = createApp(App)
app.config.globalProperties.$http = axios

app.use(store)
app.use(ElementPlus)
app.use(Antd)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.mount('#app')
