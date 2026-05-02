import json
import urllib.request
import os
import concurrent.futures

# The raw project data from earlier
project_data = {
  "screenInstances": [
    {"id": "19770804cf7a46fe8487ef658c22161c", "sourceScreen": "projects/10380042700551984895/screens/19770804cf7a46fe8487ef658c22161c"},
    {"id": "1c3473ffc56346d4bd36c490a6a64aa8", "sourceScreen": "projects/10380042700551984895/screens/1c3473ffc56346d4bd36c490a6a64aa8"},
    {"id": "1c373da3744440acaaab600e978ec147", "sourceScreen": "projects/10380042700551984895/screens/1c373da3744440acaaab600e978ec147"},
    {"id": "1cf134b1b77548b68f3fc719ea16de1c", "sourceScreen": "projects/10380042700551984895/screens/1cf134b1b77548b68f3fc719ea16de1c"},
    {"id": "1e879ac36e214627b6ddd47bed307ffe", "sourceScreen": "projects/10380042700551984895/screens/1e879ac36e214627b6ddd47bed307ffe"},
    {"id": "2781ad9e001a4349a5f43b200e27cd92", "sourceScreen": "projects/10380042700551984895/screens/2781ad9e001a4349a5f43b200e27cd92"},
    {"id": "2d213175f11948ed9366df51baf9d4d8", "sourceScreen": "projects/10380042700551984895/screens/2d213175f11948ed9366df51baf9d4d8"},
    {"id": "2e480fb741c5466081e59c738ee950bf", "sourceScreen": "projects/10380042700551984895/screens/2e480fb741c5466081e59c738ee950bf"},
    {"id": "328d89dc846346c0b05ff979c2bc87ab", "sourceScreen": "projects/10380042700551984895/screens/328d89dc846346c0b05ff979c2bc87ab"},
    {"id": "3b7b162796a24e178f04e3306f7fe13e", "sourceScreen": "projects/10380042700551984895/screens/3b7b162796a24e178f04e3306f7fe13e"},
    {"id": "4614c183122b46ecad69d60d4a61cb96", "sourceScreen": "projects/10380042700551984895/screens/4614c183122b46ecad69d60d4a61cb96"},
    {"id": "4feeba1e013e4f9eab2ffacce74291e8", "sourceScreen": "projects/10380042700551984895/screens/4feeba1e013e4f9eab2ffacce74291e8"},
    {"id": "5127ef1d652e4f0398ccd345758514c5", "sourceScreen": "projects/10380042700551984895/screens/5127ef1d652e4f0398ccd345758514c5"},
    {"id": "5539c17085ad4dd5b2f63028474b228a", "sourceScreen": "projects/10380042700551984895/screens/5539c17085ad4dd5b2f63028474b228a"},
    {"id": "55da3fa3f6ef4d33b59e80b4c5eba448", "sourceScreen": "projects/10380042700551984895/screens/55da3fa3f6ef4d33b59e80b4c5eba448"},
    {"id": "5862ecdeefb64137a9c0f7531587f7c0", "sourceScreen": "projects/10380042700551984895/screens/5862ecdeefb64137a9c0f7531587f7c0"},
    {"id": "697f8da751b84d16816335daea374d01", "sourceScreen": "projects/10380042700551984895/screens/697f8da751b84d16816335daea374d01"},
    {"id": "51903d366b024784b472f7eca445d22b", "sourceScreen": "projects/10380042700551984895/screens/51903d366b024784b472f7eca445d22b"},
    {"id": "b24c64539c6a4ab8a7680dbcd5386f24", "sourceScreen": "projects/10380042700551984895/screens/b24c64539c6a4ab8a7680dbcd5386f24"},
    {"id": "6fa6c82b816f49d18ab99d0dfa4ee2fd", "sourceScreen": "projects/10380042700551984895/screens/6fa6c82b816f49d18ab99d0dfa4ee2fd"},
    {"id": "78716c315e664f28be14b0fc16983010", "sourceScreen": "projects/10380042700551984895/screens/78716c315e664f28be14b0fc16983010"},
    {"id": "7e4bd5c6aa7c41ff9319a8a282ef8cb5", "sourceScreen": "projects/10380042700551984895/screens/7e4bd5c6aa7c41ff9319a8a282ef8cb5"},
    {"id": "8237789a111d41daa4f5957f549d75d6", "sourceScreen": "projects/10380042700551984895/screens/8237789a111d41daa4f5957f549d75d6"},
    {"id": "82a313bc9a3d4bc99bd2b432cb549ea5", "sourceScreen": "projects/10380042700551984895/screens/82a313bc9a3d4bc99bd2b432cb549ea5"},
    {"id": "841191b6e3d14c01830c91b4e13549e7", "sourceScreen": "projects/10380042700551984895/screens/841191b6e3d14c01830c91b4e13549e7"},
    {"id": "86b403d69456423ba0f3a27fae3f4e64", "sourceScreen": "projects/10380042700551984895/screens/86b403d69456423ba0f3a27fae3f4e64"},
    {"id": "87c9584895e14a59a492bad0ff398f04", "sourceScreen": "projects/10380042700551984895/screens/87c9584895e14a59a492bad0ff398f04"},
    {"id": "8a862a89c580418f9229e886a84b951e", "sourceScreen": "projects/10380042700551984895/screens/8a862a89c580418f9229e886a84b951e"},
    {"id": "91e5b4809c364b44a7f81467d28aa634", "sourceScreen": "projects/10380042700551984895/screens/91e5b4809c364b44a7f81467d28aa634"},
    {"id": "9484869833a84bf59eb1e0af3f94b092", "sourceScreen": "projects/10380042700551984895/screens/9484869833a84bf59eb1e0af3f94b092"},
    {"id": "97b9dbefd2b942cc99ae08d1e2eb9530", "sourceScreen": "projects/10380042700551984895/screens/97b9dbefd2b942cc99ae08d1e2eb9530"},
    {"id": "9a048db17bb54996a40c62ec5e74f4f5", "sourceScreen": "projects/10380042700551984895/screens/9a048db17bb54996a40c62ec5e74f4f5"},
    {"id": "9a32a3fb90b446ae9afb8dbd48d4feef", "sourceScreen": "projects/10380042700551984895/screens/9a32a3fb90b446ae9afb8dbd48d4feef"},
    {"id": "9a90f1c02c3f4eb4b012438eb8059140", "sourceScreen": "projects/10380042700551984895/screens/9a90f1c02c3f4eb4b012438eb8059140"},
    {"id": "9c902d909f90416b8febc13b4fcc8dc5", "sourceScreen": "projects/10380042700551984895/screens/9c902d909f90416b8febc13b4fcc8dc5"},
    {"id": "9cc6da5ff194405999610af7dfba1716", "sourceScreen": "projects/10380042700551984895/screens/9cc6da5ff194405999610af7dfba1716"},
    {"id": "a2f86f720c2c418f91494a522b92f84b", "sourceScreen": "projects/10380042700551984895/screens/a2f86f720c2c418f91494a522b92f84b"},
    {"id": "acadb66f584643f69abc45d7b469e0a0", "sourceScreen": "projects/10380042700551984895/screens/acadb66f584643f69abc45d7b469e0a0"},
    {"id": "b1605ae432d84b00a87bd4ef99210822", "sourceScreen": "projects/10380042700551984895/screens/b1605ae432d84b00a87bd4ef99210822"},
    {"id": "c1ccdd3e06cc489884db20a6f7661d05", "sourceScreen": "projects/10380042700551984895/screens/c1ccdd3e06cc489884db20a6f7661d05"},
    {"id": "c503deb60a004458bf6e936946c96109", "sourceScreen": "projects/10380042700551984895/screens/c503deb60a004458bf6e936946c96109"},
    {"id": "c5317715dae64d70b44569342ece58cb", "sourceScreen": "projects/10380042700551984895/screens/c5317715dae64d70b44569342ece58cb"},
    {"id": "d05753ebc7dd44d8b9a52985d9787aae", "sourceScreen": "projects/10380042700551984895/screens/d05753ebc7dd44d8b9a52985d9787aae"},
    {"id": "d3f1acdb0f0b4492b3332cebfabb7d36", "sourceScreen": "projects/10380042700551984895/screens/d3f1acdb0f0b4492b3332cebfabb7d36"},
    {"id": "e29e5edff1034b3e8e1099b572466ec8", "sourceScreen": "projects/10380042700551984895/screens/e29e5edff1034b3e8e1099b572466ec8"},
    {"id": "eb34c022baa045dbafab586d774e766e", "sourceScreen": "projects/10380042700551984895/screens/eb34c022baa045dbafab586d774e766e"},
    {"id": "ee150b0d46bc4cb892774953254abd07", "sourceScreen": "projects/10380042700551984895/screens/ee150b0d46bc4cb892774953254abd07"},
    {"id": "f7e063d9f3714701adbaa3b3cad56538", "sourceScreen": "projects/10380042700551984895/screens/f7e063d9f3714701adbaa3b3cad56538"},
    {"id": "fcb498712c44441485cedd879ed11e7e", "sourceScreen": "projects/10380042700551984895/screens/fcb498712c44441485cedd879ed11e7e"},
    {"id": "ffa06f4ce51b43079a5623e723eaef04", "sourceScreen": "projects/10380042700551984895/screens/ffa06f4ce51b43079a5623e723eaef04"}
  ]
}

# Generate a list of MCP calls we need to make to get all screens
commands = []
for instance in project_data['screenInstances']:
    if 'sourceScreen' in instance:
        screen_id = instance['sourceScreen'].split('/')[-1]
        commands.append(f'mcp__StitchMCP__get_screen(name="{instance["sourceScreen"]}", projectId="10380042700551984895", screenId="{screen_id}")')

with open('.stitch/fetch_commands.txt', 'w') as f:
    for cmd in commands:
        f.write(f'{cmd}\n')
