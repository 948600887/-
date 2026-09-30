# 东方财富妙想Skill API 安装指南

本文档介绍了如何安装和使用东方财富妙想金融Skill服务。

## 前置条件

- Node.js 22 或更高版本
- 有效的 API Key

## 安装步骤

### 1. 清理旧版本

```bash
rm -rf ~/.openclaw/skills/mx-skills*
rm -rf ~/.openclaw/workspace/skills/mx-skills*
rm -rf ~/.openclaw/workspace/agent/skills/mx-skills*
rm -rf ~/mx-skills*
rm -rf ~/mx-data*
rm -rf ~/mx-search*
rm -rf ~/mx-xuangu*
rm -rf ~/mx-zixuan*
rm -rf ~/mx-moni*
echo "✅ 已清理旧版本的妙想技能目录。"
```

### 2. 下载妙想Skills

#### 金融数据 Skill
```bash
curl -L "https://marketing.dfcfw.com/res/download/A620260331IHX67H.zip" -o mx-data.zip
unzip mx-data.zip -d ~/skills/
```

#### 资讯搜索 Skill
```bash
curl -L "https://marketing.dfcfw.com/res/download/A620260331K5WDTK.zip" -o mx-search.zip
unzip mx-search.zip -d ~/skills/
```

#### 智能选股 Skill
```bash
curl -L "https://marketing.dfcfw.com/res/download/A620260331NXBVEY.zip" -o mx-xuangu.zip
unzip mx-xuangu.zip -d ~/skills/
```

#### 自选股管理 Skill
```bash
curl -L "https://marketing.dfcfw.com/res/download/A6202603314TMGR1.zip" -o mx-zixuan.zip
unzip mx-zixuan.zip -d ~/skills/
```

#### 模拟组合管理 Skill
```bash
curl -L "https://marketing.dfcfw.com/res/download/A6202605083WYMDX.zip" -o mx-moni.zip
unzip mx-moni.zip -d ~/skills/
```

### 3. 配置 API Key

在当前会话中设置环境变量：

```bash
export MX_APIKEY="mkt_vRkDVULpNg5kf9OTwAQRaF7bii1pYmSyIFN7UalUkuQ"
```

### 4. 验证安装

```bash
test -f ~/skills/mx-data/SKILL.md && echo "✅ mx-data 已安装" || echo "❌ mx-data 未安装"
test -f ~/skills/mx-search/SKILL.md && echo "✅ mx-search 已安装" || echo "❌ mx-search 未安装"
test -f ~/skills/mx-xuangu/SKILL.md && echo "✅ mx-xuangu 已安装" || echo "❌ mx-xuangu 未安装"
test -f ~/skills/mx-zixuan/SKILL.md && echo "✅ mx-zixuan 已安装" || echo "❌ mx-zixuan 未安装"
test -f ~/skills/mx-moni/SKILL.md && echo "✅ mx-moni 已安装" || echo "❌ mx-moni 未安装"
echo "MX_APIKEY=${MX_APIKEY:+已设置}"
```

## API 接口

基础地址：`https://mkapi2.dgcfs.com/finskillshub/api/claw/`

### 金融数据查询

```bash
curl -X POST 'https://mkapi2.dgcfs.com/finskillshub/api/claw/query' \
  --header 'Content-Type: application/json' \
  --header 'apikey: mkt_vRkDVULpNg5kf9OTwAQRaF7bii1pYmSyIFN7UalUkuQ' \
  --data '{"toolQuery": "东方财富最新价"}'
```

### 资讯搜索

```bash
curl -X POST 'https://mkapi2.dgcfs.com/finskillshub/api/claw/news-search' \
  --header 'Content-Type: application/json' \
  --header 'apikey: mkt_vRkDVULpNg5kf9OTwAQRaF7bii1pYmSyIFN7UalUkuQ' \
  --data '{"toolQuery": "新能源政策"}'
```

### 智能选股

```bash
curl -X POST 'https://mkapi2.dgcfs.com/finskillshub/api/claw/stock-screen' \
  --header 'Content-Type: application/json' \
  --header 'apikey: mkt_vRkDVULpNg5kf9OTwAQRaF7bii1pYmSyIFN7UalUkuQ' \
  --data '{"toolQuery": "PE小于10的芯片股"}'
```

## 使用限制

- 每日免费调用次数：50次
- 限制重置时间：次日0点
