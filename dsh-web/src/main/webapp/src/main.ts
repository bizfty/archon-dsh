// 入口：Vue 3 + Element Plus（全量导入，简单可靠）。
import { createApp } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import App from './App.vue';
import './styles.css';

createApp(App).use(ElementPlus).mount('#app');
