import urllib.request
import json
import subprocess
import getpass
import os

def run_cmd(cmd):
    try:
        res = subprocess.run(cmd, shell=True, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return res.stdout.decode('utf-8').strip()
    except subprocess.CalledProcessError as e:
        print(f"Error running command '{cmd}': {e.stderr.decode('utf-8').strip()}")
        return None

def main():
    print("==================================================")
    print("   SCROLLLER PRO - GITHUB AUTO-PUSH CONFIGURATOR")
    print("==================================================")
    print("This helper script will:")
    print(" 1. Create a PUBLIC repository named 'scrolller-app' on your GitHub.")
    print(" 2. Configure local git files and commit your code.")
    print(" 3. Automatically push to your new public repository.")
    print("--------------------------------------------------")

    username = input("Enter your GitHub username: ").strip()
    if not username:
        print("Username is required!")
        return

    print("Please generate a GitHub Personal Access Token (PAT) with 'repo' scope at:")
    print("https://github.com/settings/tokens")
    token = getpass.getpass("Enter your GitHub PAT (input is hidden): ").strip()
    if not token:
        print("Token is required!")
        return

    # 1. Create public repo via GitHub API
    print("\n[+] Creating PUBLIC repository 'scrolller-app' on GitHub...")
    url = "https://api.github.com/user/repos"
    payload = {
        "name": "scrolller-app",
        "private": False,
        "description": "Premium ad-free visual client for Scrolller.com"
    }
    
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode('utf-8'),
        headers={
            'Authorization': f'token {token}',
            'Accept': 'application/vnd.github.v3+json',
            'User-Agent': 'ScrolllerAppBuilder'
        },
        method='POST'
    )

    try:
        with urllib.request.urlopen(req) as response:
            res_data = json.loads(response.read().decode('utf-8'))
            html_url = res_data['html_url']
            print(f"[*] Success! Repository created at: {html_url}")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode('utf-8')
        try:
            err_json = json.loads(err_body)
            msg = err_json.get('message', '')
            if "already exists" in msg:
                print("[*] Repository already exists on your GitHub account. Proceeding to push...")
            else:
                print(f"[-] API Error ({e.code}): {msg}")
                return
        except:
            print(f"[-] HTTP Error ({e.code}): {err_body}")
            return
    except Exception as e:
        print(f"[-] Failed to connect: {e}")
        return

    # 2. Local git setup
    print("\n[+] Initializing local Git repository...")
    if not os.path.exists('.git'):
        run_cmd("git init")
    
    # Configure local config name/email if not set
    email_check = run_cmd("git config --get user.email")
    if not email_check:
        user_email = input("Enter your email for Git commits: ").strip()
        run_cmd(f"git config user.email '{user_email}'")
        
    name_check = run_cmd("git config --get user.name")
    if not name_check:
        user_name = input("Enter your name for Git commits: ").strip()
        run_cmd(f"git config user.name '{user_name}'")

    print("[+] Adding files and committing...")
    run_cmd("git add .")
    run_cmd('git commit -m "feat: complete scrolller client with adblock, sorting, fullscreen & favorites"')

    # 3. Push to private repo
    print("[+] Linking and pushing code to GitHub...")
    # Remove existing remote if present
    run_cmd("git remote remove origin")
    
    # Add remote with credentials embedded
    remote_url = f"https://{username}:{token}@github.com/{username}/scrolller-app.git"
    run_cmd(f"git remote add origin {remote_url}")
    
    # Checkout main branch
    run_cmd("git branch -M main")
    
    # Push
    print("[*] Uploading files (this triggers the Actions build)...")
    push_res = run_cmd("git push -u origin main")
    
    if push_res is not None:
        print("\n==================================================")
        print("🎉 SUCCESS! CODE COMMITTED AND PUSHED TO GITHUB!")
        print("==================================================")
        print("GitHub Actions is now compiling your APK!")
        print(f"Go to: https://github.com/{username}/scrolller-app/actions")
        print("Once the build completes, download the APK from the artifacts.")
        print("==================================================")
    else:
        print("[-] Failed to push. Double-check your username, PAT token permissions (must have 'repo' scope), or network.")

if __name__ == '__main__':
    main()
