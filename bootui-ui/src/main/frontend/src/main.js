import {createApp} from 'vue'
import {createRouter, createWebHashHistory} from 'vue-router'
import 'bootstrap/dist/css/bootstrap.min.css'
import './generated/bootstrap-icons.css'
import './assets/theme-graphite.css'
import './assets/theme-cyberpunk.css'
import './assets/theme-dsfr.css'
import './assets/theme-minimal.css'
import './assets/theme-win95.css'
import App from './App.vue'
import {routes} from './routes.js'

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

createApp(App).use(router).mount('#app')
