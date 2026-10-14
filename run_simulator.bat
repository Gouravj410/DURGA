@echo off
title DURGA Laptop Simulator Launcher
echo =================================================================
echo   DURGA - Dynamic Unified Response & Geo-Alert System
echo   Laptop Interactive Demonstration & Simulator Launcher
echo =================================================================
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_simulation.ps1"
pause
