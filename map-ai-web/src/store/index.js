import { createStore } from 'vuex'

export default createStore({
  state: {
    sharedObject: null,
    error: null,
    currentPlanId: '',
    planCommands: [],
    planSaved: false
  },
  mutations: {
    SET_SHARED_OBJECT(state, payload) {
      state.sharedObject = payload
    },
    SET_STATE(state, payload) {
      if (!payload || typeof payload !== 'object') return
      Object.assign(state, payload)
    },
    SET_ERROR(state, payload) {
      state.error = payload
    },
    SET_PLAN_ID(state, payload) {
      state.currentPlanId = typeof payload === 'string' ? payload : ''
    },
    APPEND_PLAN_COMMANDS(state, payload) {
      const commands = Array.isArray(payload) ? payload.filter(Boolean) : []
      if (!commands.length) return
      state.planCommands = state.planCommands.concat(commands)
      state.planSaved = false
    },
    RESET_PLAN_COMMANDS(state, payload = {}) {
      state.planCommands = Array.isArray(payload.commands) ? payload.commands : []
      state.planSaved = !!payload.saved
    },
    SET_PLAN_SAVED(state, payload) {
      state.planSaved = !!payload
    }
  },
  actions: {
    updateSharedObject({ commit }, payload) {
      commit('SET_SHARED_OBJECT', payload)
    },
    updateState({ commit }, payload) {
      commit('SET_STATE', payload)
    },
    updateError({ commit }, payload) {
      commit('SET_ERROR', payload)
    },
    setCurrentPlanId({ commit }, payload) {
      commit('SET_PLAN_ID', payload)
    },
    appendPlanCommands({ commit }, payload) {
      commit('APPEND_PLAN_COMMANDS', payload)
    },
    resetPlanCommands({ commit }, payload) {
      commit('RESET_PLAN_COMMANDS', payload)
    },
    setPlanSaved({ commit }, payload) {
      commit('SET_PLAN_SAVED', payload)
    }
  }
})
