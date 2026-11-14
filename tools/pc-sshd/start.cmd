@echo off
REM pc-sshd 启动器（用户级，不需要管理员）。
REM 双击运行即可；关掉这个窗口服务就停。
REM 想开机自启：把「开始菜单\程序\启动」里放一个本文件的快捷方式（install-autostart.cmd 会代劳）。
setlocal
cd /d "%~dp0"
if not exist node_modules (
  echo 首次运行：安装依赖 ssh2 ...
  call npm install --no-audit --no-fund
)
node pc-sshd.mjs %*
echo.
echo pc-sshd 已退出（上面是原因）。
pause
