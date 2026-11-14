# Elasticsearch 8.18.8 + IK 中文分词插件
# 说明：EsProduct 的 mapping 使用 ik_max_word / ik_smart，官方镜像不带 IK，
#       直接启动会报 mapper_parsing_exception: analyzer [ik_max_word] has not been configured。
#       插件预装进镜像，容器重建后不会丢失。
# 插件包下载（需走代理，4.5MB）：
#   curl -L -o deploy/es/analysis-ik-8.18.8.zip https://get.infini.cloud/elasticsearch/analysis-ik/8.18.8
FROM elasticsearch:8.18.8

COPY analysis-ik-8.18.8.zip /tmp/analysis-ik.zip

# 注意：不要在本行追加 `rm -f /tmp/analysis-ik.zip`，ES 8.18 镜像的安全策略会让 rm 报
# "Operation not permitted"，导致构建失败；插件装好后该临时文件留在镜像层，不影响运行。
RUN bin/elasticsearch-plugin install --batch file:///tmp/analysis-ik.zip
