import axios from "axios";

// Every call goes through the api-gateway
const localHost = "http://localhost:5000";

const api = axios.create({
  baseURL: localHost,
  headers: {
    "Content-Type": "application/json",
  },
});

export default api;
