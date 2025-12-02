require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "NitroCloudUploader"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => min_ios_version_supported }
  s.source       = { :git => "https://github.com/Gautham495/react-native-nitro-cloud-uploader.git", :tag => "#{s.version}" }

  s.source_files = [
    "ios/**/*.{swift}",
    "ios/**/*.{m,mm}",
    "cpp/**/*.{hpp,cpp}",
  ]

  # Swift 5.5+ required for async/await and TaskGroup
  s.swift_version = "5.5"

  # iOS frameworks needed for:
  # - Foundation: URLSession, FileManager
  # - Network: NWPathMonitor for network monitoring
  s.frameworks = [
    "Foundation",
    "Network",
  ]

  # React Native dependencies
  s.dependency 'React-jsi'
  s.dependency 'React-callinvoker'

  # Nitro autolinking
  load 'nitrogen/generated/ios/NitroCloudUploader+autolinking.rb'
  add_nitrogen_files(s)

  install_modules_dependencies(s)
end