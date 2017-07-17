// The Vue build version to load with the `import` command
// (runtime-only or standalone) has been set in webpack.base.conf with an alias.
import Vue from 'vue'
import App from './App'
import router from './router'
// import axios from 'axios'

import './assets/inspinia/css/style.css'
import './assets/inspinia/css/bootstrap.min.css'
import './assets/inspinia/css/animate.css'
import './assets/inspinia/font-awesome/css/font-awesome.css'

import './constant/global'

Vue.config.productionTip = false
// Vue.use(axios)

/* eslint-disable no-new */
new Vue({
  el: '#app',
  router,
  template: '<App/>',
  components: { App }
})
