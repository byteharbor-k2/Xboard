当前环境架构                                                                                                                 
                                                                                                                            
┌──────────────┬─────────────────────────┬───────────────────────────┐                                                       
│              │       本地 (Mac)        │      生产 (AWS VPS)       │                                                       
├──────────────┼─────────────────────────┼───────────────────────────┤                                                       
│ URL          │ http://localhost:7001   │ https://app.howiecode.xyz │                                                       
├──────────────┼─────────────────────────┼───────────────────────────┤
│ 镜像         │ xboard:latest（单容器） │ xboard:new（多容器）      │                                                       
├──────────────┼─────────────────────────┼───────────────────────────┤                                                       
│ compose.yaml │ 各自独立，不同步        │ 各自独立，不同步          │                                                       
├──────────────┼─────────────────────────┼───────────────────────────┤                                                       
│ .env         │ SQLite, localhost:7001  │ SQLite, app.howiecode.xyz │                                                     
├──────────────┼─────────────────────────┼───────────────────────────┤                                                       
│ 定制文件     │ Git 管理，本地直接挂载  │ rsync 推送                │                                                     
└──────────────┴─────────────────────────┴───────────────────────────┘                                                       
                                                                                                                            
日常开发部署流程                                                                                                             
                                                                                                                            
1. 本地改代码（theme/Freedom/、routes/web.php）                                                                              
2. 浏览器 http://localhost:7001 验证
3. git add → git commit → git push                                                                                           
4. rsync 到 VPS:                                                                                                           
    rsync -avz theme/Freedom/ AWS-SG-panel:/opt/Xboard/theme/Freedom/                                                         
    rsync -avz routes/web.php AWS-SG-panel:/opt/Xboard/routes/web.php                                                         
5. ssh AWS-SG-panel 'cd /opt/Xboard && sudo docker compose exec web php artisan cache:clear && sudo docker compose exec web  
php artisan view:clear && sudo docker compose restart web'                                                                   
                
注意事项                                                                                                                     
                
- docker compose restart = 重启现有容器（不读新的 compose.yaml）                                                             
- docker compose up -d = 重建容器（读新的 compose.yaml，新 volume 才生效）
- 改了 compose.yaml 的 volume 挂载后要用 up -d 不是 restart                                                                  
                                                                    