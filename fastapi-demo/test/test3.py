print("=" * 40)
print("类型转换：int() / str() / float() / bool() 🔄")
print("=" * 40)


# int()：转整数
print(int("100"))    # 100
print(int(3.14))    # 3（截断小数，不是四舍五入）
print(int(True))    # 1
print(int(False))   # 0

# float()：转浮点数
print(float("3.14"))  # 3.14
print(float(100))    # 100.0

# str()：转字符串
print(str(100))     # "100"
print(str(3.14))    # "3.14"
print(str(True))    # "True"

# bool()：转布尔值
print(bool(1))     # True
print(bool(0))     # False
print(bool("hello"))  # True
print(bool(""))     # False




print("=" * 40)
print(" 坑坑坑坑坑 🔄")
print("=" * 40)

# 坑1：float转int直接截断，不是四舍五入
print(int(3.9))  # 3，不是4！
print(int(-3.9)) # -3，不是-4！

# 如果需要四舍五入，用round()
print(round(3.9))  # 4
print(round(3.4))  # 3

# 坑2：空字符串转int会报错
# print(int(""))  # ❌ ValueError: invalid literal for int()

# 坑3：带空格的字符串要先strip
# print(int(" 100 ".strip()))  # 100

# 坑4：None不能转int
# print(int(None))  # ❌ TypeError: int() argument must be a string or a number, not 'NoneType'





print("=" * 40)
print("安全的类型转换（异常处理）")
print("=" * 40)

# 方式1：try-except（和Java的try-catch一样）
user_input = "abc"
try:
  number = int(user_input)
  print(f"转换成功：{number}")
except ValueError:
  print("转换失败：请输入数字")

# 方式2：判断后再转换（更推荐）
user_input = "100"
if user_input.isdigit():
  number = int(user_input)
  print(f"转换成功：{number}")
else:
  print("转换失败：请输入数字")




print("=" * 40)
print("type() vs isinstance()：查看和判断类型 🔍")
print("=" * 40)

age = 25
name = "Java程序员"

print(type(age))   #
print(type(name))  #

# 判断类型是否相等
print(type(age) == int)   # True
print(type(name) == str)   # True








print("=" * 40)
print("isinstance() —— 判断类型（更推荐）")
print("=" * 40)

age = 25
salary = 35000.50

# 判断单个类型
print(isinstance(age, int))       # True
print(isinstance(salary, float))    # True

# 判断多个类型（用元组）
print(isinstance(age, (int, float)))   # True
print(isinstance(salary, (int, float))) # True

# 实际应用：确保参数是数字
def calculate_bonus(salary):
  if not isinstance(salary, (int, float)):
    raise TypeError("薪资必须是数字")
  return salary * 0.1

print(calculate_bonus(35000))  # 3500.0
print(calculate_bonus("35000")) # ❌ TypeError