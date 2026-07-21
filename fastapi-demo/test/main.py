# 这是单行注释，和Java的//一样
print("Hello AI，Java程序员来了！")   # 注释也可以写在代码后面


"""
这是多行注释
可以写很多行
和Java的 /* */ 类似
"""
'''
也可以用三个单引号
效果完全一样
'''
name = "Java程序员"
years = 10
print(f"我是{name},做了{years}年开发，现在学AI")



def greet(name):
    """
    向用户打招呼
    Args:
        name: 用户名
    Returns:
        问候语字符串
    """
    return f"Hello, {name}!"

print(greet("AI"))




def main():
    print("~~~Hello AI~~~")
    # 其他代码...
if __name__ == "__main__":
    main()