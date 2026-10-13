@echo off
rem Unified pest+weed demo launcher (ASCII only, safe encoding)
cd /d "%~dp0"
set PATH=D:\conda\envs\pd312\Library\bin;D:\AIC\gpu_libs\cudnn_pip\nvidia\cudnn\bin;%PATH%
set MPLCONFIGDIR=D:\AIC\tmp_mpl
D:\conda\envs\pd312\python.exe app.py
