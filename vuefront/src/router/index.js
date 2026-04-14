import { createRouter, createWebHistory } from 'vue-router'
import App from '../App.vue'
import AuthPortal from '../views/AuthPortal.vue'
import MinePage from '../views/MinePage.vue'
import Zhi from '../components/Zhi.vue'
import { getToken } from '../utils/token'

const routes = [
  {
    path: '/',
    component: App,
    children: [
      {
        path: '',
        name: 'home',
        component: Zhi,
      },
      {
        path: 'login',
        name: 'login',
        component: AuthPortal,
        props: { mode: 'register' },
      },
      {
        path: 'register',
        name: 'register',
        component: AuthPortal,
        props: { mode: 'login' },
      },
      {
        path: 'add',
        name: 'add-enterprise',
        component: AuthPortal,
        props: { mode: 'add' },
      },
      {
        path: 'mine',
        name: 'mine',
        component: MinePage,
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const hasToken = Boolean(getToken())

  if (to.name === 'mine' && !hasToken) {
    return { name: 'login' }
  }

  return true
})

export default router
