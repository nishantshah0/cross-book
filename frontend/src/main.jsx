import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App.jsx";
import Home from "./Home.jsx";
import Surveillance from "./Surveillance.jsx";
import "@fontsource-variable/inter";
import "@fontsource/jetbrains-mono/400.css";
import "@fontsource/jetbrains-mono/500.css";
import "./style.css";
import "./home.css";
import "./surveillance.css";
const path = window.location.pathname;
const page = /^\/terminal\/?$/.test(path)
  ? "terminal"
  : /^\/surveillance\/?$/.test(path)
    ? "surveillance"
    : "home";
document.title = {
  terminal: "CrossBook — Trading terminal",
  surveillance: "CrossBook — Surveillance",
  home: "CrossBook — Trade on a real order book",
}[page];
createRoot(document.getElementById("root")).render(
  page === "terminal" ? (
    <App />
  ) : page === "surveillance" ? (
    <Surveillance />
  ) : (
    <Home />
  ),
);
