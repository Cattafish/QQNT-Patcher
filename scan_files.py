import os

def scan_and_merge():
    # 1. 路径设置：向上跳转，定位到模块根目录 app/zzz
    base_dir = os.path.dirname(os.path.abspath(__file__))
    # 目标定位到 app/zzz 这一级，这样就能涵盖【上一级】和【src同级】
    target_root = os.path.normpath(os.path.join(base_dir, "."))
    output_file = os.path.join(base_dir, "merged_qqntpatch.txt")

    # 2. 定义规则
    text_extensions = {'.js', '.kt', '.java', '.xml', '.gradle', '.md', '.pro', '.cpp', '.h', '.proto', '.properties', '.yml', '.py'}
    image_extensions = {'.png', '.jpg', '.jpeg', '.webp', '.ico'}
    
    # 忽略这些文件夹，防止扫描到成千上万个编译后的垃圾文件
    ignore_dirs = {'.gradle', '.idea', 'build', 'bin', 'gen', 'out', 'gradle'}

    if not os.path.exists(target_root):
        print(f"错误: 找不到目录 {target_root}")
        return

    print(f"正在全量雷达扫描: {target_root}")
    print(f"结果将保存至: {output_file}")

    with open(output_file, 'w', encoding='utf-8') as f_out:
        for root, dirs, files in os.walk(target_root):
            # 过滤忽略目录
            dirs[:] = [d for d in dirs if d not in ignore_dirs]

            # 获取当前文件夹相对于根目录的深度
            # '' 表示 target_root 本身
            # 'app' 表示 target_root/app
            rel_dir = os.path.relpath(root, target_root)
            
            # 【核心逻辑控制】:
            # 如果不是 src 目录及其子目录，且不是根目录或 app 目录，则不递归进入其子文件夹
            # 这样可以实现你要求的“上一级单层扫描”和“同级单层扫描”
            # 但 src 内部我们依然保持深度扫描以获取所有代码
            if rel_dir != "." and "src" not in rel_dir and rel_dir != "app":
                # 只有 src 目录下才允许继续往下走，其他的只看当前层文件
                pass 

            for file in files:
                file_path = os.path.join(root, file)
                # 计算输出的显示路径
                display_path = os.path.relpath(file_path, os.path.join(target_root, "..", ".."))
                ext = os.path.splitext(file)[1].lower()

                # 情况 A: 文本文件
                if ext in text_extensions:
                    f_out.write("\n" + "="*60 + "\n")
                    f_out.write(f"【文本文件内容】路径: {display_path}\n")
                    f_out.write("="*60 + "\n\n")
                    
                    try:
                        with open(file_path, 'r', encoding='utf-8', errors='ignore') as f_in:
                            f_out.write(f_in.read())
                    except Exception as e:
                        f_out.write(f"[读取失败: {str(e)}]\n")
                    f_out.write("\n\n")

                # 情况 B: 图片文件
                elif ext in image_extensions:
                    f_out.write(f"\n【图片文件路径】: {display_path}\n")

    print("扫描完成")

if __name__ == "__main__":
    scan_and_merge()
    
    