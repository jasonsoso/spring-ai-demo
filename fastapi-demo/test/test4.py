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
print(int(" 100 ".strip()))  # 100

# 坑4：None不能转int
print(int(None))  # ❌ TypeError: int() argument must be a string or a number, not 'NoneType'