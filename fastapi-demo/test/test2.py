
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


print("=" * 40)
print("逻辑运算（and / or / not）")
print("=" * 40)


# and：两边都为True才为True（和Java的&&一样）
print(True and True)  # True
print(True and False) # False

# or：一边为True就为True（和Java的||一样）
print(True or False)  # True
print(False or False) # False

# not：取反（和Java的!一样）
print(not True)    # False
print(not False)    # True

# 实际应用
is_java_dev = True
has_python_exp = False
can_apply = is_java_dev and not has_python_exp
print(f"可以申请转型课程吗？{can_apply}") # True



print("=" * 40)
print("布尔值的‘真值判断’——Python的灵活之处")
print("=" * 40)

# 以下值在if判断中等价于False（"假值"）
print(bool(0))    # False
print(bool(""))    # False（空字符串）
print(bool([]))    # False（空列表）
print(bool(None))   # False
print(bool(0.0))   # False

# 以下值在if判断中等价于True（"真值"）
print(bool(1))    # True
print(bool("hello")) # True（非空字符串）
print(bool([1,2,3])) # True（非空列表）
print(bool(3.14))   # True

# 实际应用：判断字符串是否为空
name = "Java程序员"
if name:       # 等价于 if name != "":
  print(f"姓名：{name}")
else:
  print("姓名未填写")





print("=" * 40)
print("None类型——Python的【空值】 🕳️")
print("=" * 40)


# 定义None
result = None

# 类型查看
print(type(result))  #

# 判断是否为None
print(result is None)  # True（推荐用is判断）
print(result == None)  # True（也可以，但不推荐）

# 函数默认返回None
def do_nothing():
  pass

print(do_nothing())  # None






# 场景1：函数可选参数
def greet(name, greeting=None):
  if greeting is None:
    greeting = "Hello"
  return f"{greeting}, {name}!"

print(greet("Java程序员"))      # Hello, Java程序员!
print(greet("Java程序员", "Hi"))  # Hi, Java程序员!

# 场景2：初始化变量
user_input = None
while user_input is None:
  user_input = input("请输入您的姓名：")
  if user_input.strip() == "":
    user_input = None
    print("姓名不能为空，请重新输入")










print("=" * 40)
print("类型转换：int() / str() / float() / bool() 🔄")
print("=" * 40)

