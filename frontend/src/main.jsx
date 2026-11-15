import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App.jsx";
import Home from "./Home.jsx";
import "@fontsource-variable/inter";
import "@fontsource/jetbrains-mono/400.css";
import "@fontsource/jetbrains-mono/500.css";
import "./style.css";
import "./home.css";
const terminal = /^\/terminal\/?$/.test(window.location.pathname);
document.title = terminal
  ? "CrossBook — Trading terminal"
  : "CrossBook — Trade on a real order book";
createRoot(document.getElementById("root")).render(
  terminal ? <App /> : <Home />,
);
