
# 定义布尔值
is_java_dev = True
has_ai_exp = False

# 类型查看
print(type(is_java_dev))
print(f"is_java_dev:{is_java_dev},has_ai_exp:{has_ai_exp}")

# 注意大小写！Python的True/False首字母大写
# 和Java的true/false不同（Java是全小写）



age = 25

print(age > 18)  # True
print(age < 18)  # False
print(age >= 25) # True
print(age <= 30) # True
print(age == 25) # True（等于）
print(age != 30) # True（不等于）

# 连续比较（Python特色，Java不支持！）
print(18 < age < 30)  # True，等价于 18 < age and age < 30
