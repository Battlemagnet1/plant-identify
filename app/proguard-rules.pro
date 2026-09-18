# plant Identify —— ProGuard / R8 规则
#
# Phase 1 尚未开启混淆（isMinifyEnabled = false），本文件先占位。
# Phase 7 开启混淆时需补充的规则：
#   - Room 生成的实现类（Room 自带 consumer rules，一般无需额外配置）
#   - 若后续通过反射反序列化 AI 返回的 JSON，需保留对应数据类
#   - 签名信息与 API Key 绝不可写入本文件
