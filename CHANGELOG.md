**2026/02/15**

* URL参数新增timeout、followRedirects、resolveIp字段
* 更换JS引擎，修复const作用域问题
* 新增原生段评支持
* java.get/post/head增加重载方法，支持传入json字符串格式的请求头
* 引入Crypto-js，使阅读原生支持CryptoJS加解密
* 新增webview代码编辑器，支持自动补全、语法检查、格式化等
* 新增定时任务功能，使用cron表达式驱动的定时脚本执行管理

**2022/10/02**

* 更新cronet: 106.0.5249.79
* 正文选择菜单朗读按钮长按可切换朗读选择内容和从选择开始处一直朗读
* 源编辑输入框设置最大行数12,在行数特别多的时候更容易滚动到其它输入
* 修复某些情况下无法搜索到标题的bug，净化规则较多的可能会降低搜索速度 by Xwite
* 修复文件类书源换源后阅读bug by Xwite
* Cronet 支持DnsHttpsSvcb by g2s20150909
* 修复web进度同步问题 by 821938089
* 启用混淆以减小app大小 有bug请带日志反馈
* 其它一些优化
